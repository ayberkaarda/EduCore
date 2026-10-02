#!/usr/bin/env bash
# One backup run: pg_dump (custom format) piped through gzip, integrity check, weekly copy,
# retention pruning, a copy of the erasure ledger, optional csv_uploads/done archival, optional S3-compatible
# upload and a Prometheus textfile with the run's outcome.
#
# Connection: libpq variables PGHOST, PGPORT, PGUSER, PGDATABASE and PGPASSFILE (a mode-0600 file written
# by backup-entrypoint.sh from a Docker secret). PGPASSWORD is refused: it would be visible to every process.
# Settings (all optional, defaults in brackets):
#   BACKUP_ROOT [/backups]  BACKUP_KEEP_DAILY [14 days]  BACKUP_KEEP_WEEKLY [8 ISO weeks]  BACKUP_KEEP_CSV [14 days]
#   BACKUP_ARCHIVE_CSV [false]  BACKUP_CSV_DONE_DIR [/data/csv_uploads/done]
#   BACKUP_LEDGER_FILE [/data/erasure-ledger/erasure-ledger.log]
#   BACKUP_S3_BUCKET, BACKUP_S3_ENDPOINT, BACKUP_S3_ACCESS_KEY_ID, BACKUP_S3_SECRET_ACCESS_KEY,
#   BACKUP_S3_REGION, BACKUP_S3_PREFIX [educore], BACKUP_S3_PROVIDER [Other]
# Upload happens only when BACKUP_S3_BUCKET is non-empty.
set -euo pipefail
# Dumps contain personal data: new files are 0600 and new directories 0700 (owner: the backup user).
umask 077

log() { printf '%s backup: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
fail() { log "ERROR: $*" >&2; exit 1; }

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGDATABASE:?PGDATABASE is required}"
[[ -z "${PGPASSWORD:-}" ]] || fail "PGPASSWORD must not be set; provide the password as a file (BACKUP_DB_PASSWORD_FILE)"
[[ -n "${PGPASSFILE:-}" && -r "${PGPASSFILE:-}" ]] || fail "PGPASSFILE is not set or not readable (start through backup-entrypoint.sh)"

BACKUP_ROOT="${BACKUP_ROOT:-/backups}"
KEEP_DAILY="${BACKUP_KEEP_DAILY:-14}"
KEEP_WEEKLY="${BACKUP_KEEP_WEEKLY:-8}"
KEEP_CSV="${BACKUP_KEEP_CSV:-14}"
CSV_DONE_DIR="${BACKUP_CSV_DONE_DIR:-/data/csv_uploads/done}"
ARCHIVE_CSV="${BACKUP_ARCHIVE_CSV:-false}"
LEDGER_FILE="${BACKUP_LEDGER_FILE:-/data/erasure-ledger/erasure-ledger.log}"
METRICS_DIR="$BACKUP_ROOT/metrics"

for n in "$KEEP_DAILY" "$KEEP_WEEKLY" "$KEEP_CSV"; do
  [[ "$n" =~ ^[1-9][0-9]*$ ]] || fail "retention counts must be positive integers (got '$n')"
done
[[ "$ARCHIVE_CSV" == "true" || "$ARCHIVE_CSV" == "false" ]] || fail "BACKUP_ARCHIVE_CSV must be true or false (got '$ARCHIVE_CSV')"

mkdir -p "$BACKUP_ROOT/daily" "$BACKUP_ROOT/weekly" "$BACKUP_ROOT/csv-done" "$BACKUP_ROOT/ledger" "$METRICS_DIR"
chmod 0700 "$BACKUP_ROOT/daily" "$BACKUP_ROOT/weekly" "$BACKUP_ROOT/csv-done" "$BACKUP_ROOT/ledger"
# Metrics carry no personal data and are read by a node-exporter textfile collector running as another user.
chmod 0755 "$METRICS_DIR"

started_epoch="$(date -u +%s)"
ts="$(date -u -d "@$started_epoch" +%Y%m%dT%H%M%SZ)"
week="$(date -u -d "@$started_epoch" +%G-W%V)"
name="educore-${ts}.dump.gz"
daily="$BACKUP_ROOT/daily/$name"
partial="$(mktemp "$BACKUP_ROOT/daily/.partial.XXXXXX")"
dump_bytes=0
offsite_enabled=0
[[ -n "${BACKUP_S3_BUCKET:-}" ]] && offsite_enabled=1

read_state() { if [[ -r "$1" ]]; then head -n 1 "$1"; else echo 0; fi; }

# Prometheus textfile (node-exporter textfile collector format), written atomically.
write_metrics() {
  local ok="$1" tmp
  tmp="$(mktemp "$METRICS_DIR/.educore_backup.XXXXXX")"
  {
    echo "# HELP educore_backup_last_run_timestamp_seconds Start time of the most recent backup run."
    echo "# TYPE educore_backup_last_run_timestamp_seconds gauge"
    echo "educore_backup_last_run_timestamp_seconds $started_epoch"
    echo "# HELP educore_backup_last_run_success 1 if the most recent backup run completed, else 0."
    echo "# TYPE educore_backup_last_run_success gauge"
    echo "educore_backup_last_run_success $ok"
    echo "# HELP educore_backup_last_success_timestamp_seconds Start time of the most recent complete backup run."
    echo "# TYPE educore_backup_last_success_timestamp_seconds gauge"
    echo "educore_backup_last_success_timestamp_seconds $(read_state "$BACKUP_ROOT/last-success")"
    echo "# HELP educore_backup_last_dump_size_bytes Size of the most recent compressed dump."
    echo "# TYPE educore_backup_last_dump_size_bytes gauge"
    echo "educore_backup_last_dump_size_bytes $dump_bytes"
    echo "# HELP educore_backup_offsite_enabled 1 if S3-compatible upload is configured."
    echo "# TYPE educore_backup_offsite_enabled gauge"
    echo "educore_backup_offsite_enabled $offsite_enabled"
    echo "# HELP educore_backup_last_upload_success_timestamp_seconds Start time of the most recent run whose off-site upload succeeded."
    echo "# TYPE educore_backup_last_upload_success_timestamp_seconds gauge"
    echo "educore_backup_last_upload_success_timestamp_seconds $(read_state "$BACKUP_ROOT/last-upload")"
  } > "$tmp"
  chmod 0644 "$tmp"
  mv -f -- "$tmp" "$METRICS_DIR/educore_backup.prom"
}

on_exit() {
  local rc=$?
  rm -f -- "$partial"
  if [[ $rc -ne 0 ]]; then
    write_metrics 0 || true
    log "run failed (exit $rc)" >&2
  fi
}
trap on_exit EXIT

# Retention by distinct UTC days: keep the newest file of each of the newest $3 days and delete the rest
# (older files of a kept day and every file of older days). File names embed the UTC timestamp
# (<prefix>-YYYYMMDDTHHMMSSZ...), so a reverse lexical sort is newest-first. Unrecognised names are kept.
prune_days() {
  local dir="$1" pattern="$2" keep="$3" f day kept=0
  local -a files
  local -A seen=()
  mapfile -t files < <(find "$dir" -maxdepth 1 -type f -name "$pattern" | sort -r)
  for f in "${files[@]}"; do
    day="$(basename "$f" | sed -n 's/^[a-z-]*-\([0-9]\{8\}\)T[0-9]\{6\}Z.*/\1/p')"
    [[ -n "$day" ]] || continue
    if [[ -z "${seen[$day]+x}" ]] && (( kept < keep )); then
      seen[$day]=1
      kept=$((kept + 1))
      continue
    fi
    rm -f -- "$f" "$f.sha256"
    log "pruned $(basename "$f")"
  done
}

# Weekly files are named per ISO week (educore-YYYY-Www), one file per week: keep the newest $3 weeks.
prune_weeks() {
  local dir="$1" keep="$2" f
  local -a files
  mapfile -t files < <(find "$dir" -maxdepth 1 -type f -name 'educore-[0-9][0-9][0-9][0-9]-W[0-9][0-9].dump.gz' | sort -r)
  for f in "${files[@]:keep}"; do
    rm -f -- "$f" "$f.sha256"
    log "pruned $(basename "$f")"
  done
}

write_checksum() {
  (cd "$(dirname "$1")" && sha256sum "$(basename "$1")" > "$(basename "$1").sha256")
}

log "dumping database '$PGDATABASE' from host '$PGHOST'"
# --compress=0: pg_dump writes an uncompressed custom-format archive; gzip compresses the stream.
pg_dump --format=custom --compress=0 | gzip -6 > "$partial"
gzip -t "$partial"
# The archive's table of contents must be readable, otherwise the dump is not restorable.
gunzip -c "$partial" | pg_restore --list > /dev/null
mv -- "$partial" "$daily"
write_checksum "$daily"
dump_bytes="$(stat -c %s "$daily")"
log "wrote daily/$name ($(du -h "$daily" | cut -f1))"

weekly="$BACKUP_ROOT/weekly/educore-${week}.dump.gz"
if [[ ! -e "$weekly" ]]; then
  cp -- "$daily" "$weekly"
  write_checksum "$weekly"
  log "wrote weekly/$(basename "$weekly")"
fi

# Erasure ledger (keyed digests of purged accounts, no personal data): a dated copy travels with the dumps, so a
# host that lost the ledger volume can still replay erasures after restoring (docs/ops/BACKUP_RESTORE.md).
if [[ -r "$LEDGER_FILE" ]]; then
  ledger_copy="$BACKUP_ROOT/ledger/erasure-ledger-${ts}.log"
  cp -- "$LEDGER_FILE" "$ledger_copy"
  write_checksum "$ledger_copy"
  log "copied the erasure ledger ($(wc -l < "$ledger_copy") entries) to ledger/$(basename "$ledger_copy")"
else
  log "no erasure ledger at $LEDGER_FILE (nothing purged yet, or the volume is not mounted)"
fi

# Processed CSV files hold names and student numbers. They are no longer archived by default: an archive would keep a
# purged student for BACKUP_KEEP_CSV more days, and the backend deletes processed files right after the import.
if [[ "$ARCHIVE_CSV" != "true" ]]; then
  log "BACKUP_ARCHIVE_CSV is not true; csv_uploads/done archival skipped"
elif [[ -d "$CSV_DONE_DIR" ]] && [[ -n "$(find "$CSV_DONE_DIR" -mindepth 1 -print -quit)" ]]; then
  archive="$BACKUP_ROOT/csv-done/csv-done-${ts}.tar.gz"
  tar -czf "$archive" -C "$(dirname "$CSV_DONE_DIR")" "$(basename "$CSV_DONE_DIR")"
  write_checksum "$archive"
  log "archived $CSV_DONE_DIR to csv-done/$(basename "$archive")"
else
  log "no processed CSV files in $CSV_DONE_DIR; archival skipped"
fi

prune_days "$BACKUP_ROOT/daily" 'educore-*.dump.gz' "$KEEP_DAILY"
prune_weeks "$BACKUP_ROOT/weekly" "$KEEP_WEEKLY"
# Existing archives age out even when archival is off.
prune_days "$BACKUP_ROOT/csv-done" 'csv-done-*.tar.gz' "$KEEP_CSV"
prune_days "$BACKUP_ROOT/ledger" 'erasure-ledger-*.log' "$KEEP_DAILY"

if [[ "$offsite_enabled" == 1 ]]; then
  : "${BACKUP_S3_ENDPOINT:?BACKUP_S3_ENDPOINT is required when BACKUP_S3_BUCKET is set}"
  : "${BACKUP_S3_ACCESS_KEY_ID:?BACKUP_S3_ACCESS_KEY_ID is required when BACKUP_S3_BUCKET is set}"
  : "${BACKUP_S3_SECRET_ACCESS_KEY:?BACKUP_S3_SECRET_ACCESS_KEY is required when BACKUP_S3_BUCKET is set}"
  # rclone reads the remote definition from RCLONE_CONFIG_<REMOTE>_* variables; nothing is written to disk.
  export RCLONE_CONFIG_OFFSITE_TYPE=s3
  export RCLONE_CONFIG_OFFSITE_PROVIDER="${BACKUP_S3_PROVIDER:-Other}"
  export RCLONE_CONFIG_OFFSITE_ENDPOINT="$BACKUP_S3_ENDPOINT"
  export RCLONE_CONFIG_OFFSITE_REGION="${BACKUP_S3_REGION:-}"
  export RCLONE_CONFIG_OFFSITE_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY_ID"
  export RCLONE_CONFIG_OFFSITE_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_ACCESS_KEY"
  export RCLONE_CONFIG_OFFSITE_ENV_AUTH=false
  target="offsite:${BACKUP_S3_BUCKET}/${BACKUP_S3_PREFIX:-educore}"
  # copy uploads new and changed files and never deletes remote objects itself. That alone does not protect
  # remote history (anyone holding the key can delete or overwrite); bucket versioning/Object Lock and a
  # key without delete permission do (docs/ops/BACKUP_RESTORE.md).
  rclone copy "$BACKUP_ROOT" "$target" --config /dev/null --s3-no-check-bucket \
    --exclude '.*' --exclude 'last-*' --exclude 'metrics/**' --log-level NOTICE
  printf '%s\n' "$started_epoch" > "$BACKUP_ROOT/last-upload"
  log "uploaded to S3-compatible bucket '${BACKUP_S3_BUCKET}' prefix '${BACKUP_S3_PREFIX:-educore}'"
else
  log "BACKUP_S3_BUCKET not set; off-site upload skipped"
fi

printf '%s\n' "$started_epoch" > "$BACKUP_ROOT/last-success"
write_metrics 1
log "done"
