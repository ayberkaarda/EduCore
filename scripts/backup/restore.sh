#!/usr/bin/env bash
# Restore an EduCore backup into a running PostgreSQL container (by default the compose
# database container `educore-postgres`). DESTRUCTIVE: existing objects in the target database
# are dropped and recreated from the dump. Stop the backend first (docker compose stop educore-backend).
#
# Usage:
#   scripts/backup/restore.sh [--container NAME] [--database DB]
#                             [--ledger-volume NAME | --ledger-file PATH | --no-ledger | --skip-replay]
#                             --yes <dump.gz | dump>
#
#   --container NAME      target container (default: educore-postgres)
#   --database DB         target database (default: the container's POSTGRES_DB)
#   --ledger-volume NAME  erasure ledger volume for the post-restore step (default: educore_erasure_ledger)
#   --ledger-file PATH    erasure ledger as a host file (e.g. the newest ledger/ copy of the backup)
#   --no-ledger           nothing was ever purged: reset sessions without replaying erasures
#   --skip-replay         do NOT run post-restore.sh (erased accounts and revoked sessions stay live)
#   --yes                 required confirmation; without it the script only prints what it would do
#
# The dump is copied into the container, its gzip stream and archive table of contents are checked,
# and pg_restore runs in a single transaction: either the whole dump is restored or nothing changes.
# Then scripts/backup/post-restore.sh replays the erasure ledger and revokes every session (AC-09); the restore
# reports success only when that step succeeded. Authentication uses the container's local socket as the
# POSTGRES_USER role; no password is read.
set -euo pipefail
# Nothing this script creates may be readable by other users (dumps contain personal data).
umask 077
export MSYS_NO_PATHCONV=1

container="educore-postgres"
database=""
confirmed=false
dump=""
skip_replay=false
ledger_args=()
here="$(cd "$(dirname "$0")" && pwd)"

usage() { sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; }
die() { echo "restore: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --container) container="${2:?--container needs a value}"; shift 2 ;;
    --database) database="${2:?--database needs a value}"; shift 2 ;;
    --yes) confirmed=true; shift ;;
    --ledger-volume|--ledger-file) ledger_args+=("$1" "${2:?$1 needs a value}"); shift 2 ;;
    --no-ledger) ledger_args+=("$1"); shift ;;
    --skip-replay) skip_replay=true; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) usage >&2; exit 1 ;;
    *) [[ -z "$dump" ]] || die "only one dump file may be given"; dump="$1"; shift ;;
  esac
done

[[ -n "$dump" ]] || { usage >&2; exit 1; }
[[ -f "$dump" ]] || die "file not found: $dump"
command -v docker > /dev/null 2>&1 || die "docker CLI not found"
[[ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" == "true" ]] \
  || die "container '$container' is not running"

if [[ -z "$database" ]]; then
  # Read only the database name from the container environment (never the password).
  database="$(docker exec "$container" sh -c 'printf "%s" "${POSTGRES_DB:-postgres}"')"
fi

# Check the erasure ledger source before anything is dropped: a restore without it would resurrect erased accounts.
if [[ "$skip_replay" != true ]]; then
  ledger_volume="educore_erasure_ledger"
  ledger_from_volume=true
  for ((i = 0; i < ${#ledger_args[@]}; i++)); do
    case "${ledger_args[$i]}" in
      --ledger-volume) ledger_volume="${ledger_args[$((i + 1))]}" ;;
      --ledger-file)
        ledger_from_volume=false
        [[ -r "${ledger_args[$((i + 1))]}" ]] || die "ledger file not readable: ${ledger_args[$((i + 1))]}" ;;
      --no-ledger) ledger_from_volume=false ;;
    esac
  done
  if [[ "$ledger_from_volume" == true ]] && ! docker volume inspect "$ledger_volume" > /dev/null 2>&1; then
    die "erasure ledger volume '$ledger_volume' not found; pass --ledger-file PATH (a ledger/ copy of the backup), --no-ledger if nothing was ever purged, or --skip-replay"
  fi
fi

if [[ "$confirmed" != true ]]; then
  echo "restore: would restore '$dump' into database '$database' in container '$container'."
  echo "restore: this drops and recreates every object contained in the dump. Re-run with --yes."
  exit 2
fi

# Private staging file inside the container (mktemp: unpredictable name, mode 0600), removed on any exit.
staged="$(docker exec "$container" sh -c 'umask 077 && mktemp /tmp/educore-restore.XXXXXX')" \
  || die "could not create a staging file in '$container'"
cleanup() { docker exec "$container" rm -f "$staged" "$staged.gz" > /dev/null 2>&1 || true; }
trap cleanup EXIT
trap 'exit 130' INT TERM

case "$dump" in
  *.gz) docker exec -i "$container" sh -c 'set -e; umask 077; cat > "$1.gz"; gzip -t "$1.gz"; gunzip -c "$1.gz" > "$1"; rm -f "$1.gz"' \
          sh "$staged" < "$dump" || die "gzip stream is corrupt: $dump" ;;
  *) docker exec -i "$container" sh -c 'umask 077; cat > "$1"' sh "$staged" < "$dump" ;;
esac

docker exec "$container" pg_restore --list "$staged" > /dev/null || die "not a readable pg_dump custom-format archive"
echo "restore: archive OK, restoring into '$database' in '$container'"

started=$SECONDS
docker exec "$container" sh -c '
  pg_restore -U "${POSTGRES_USER:-postgres}" -d "$1" --clean --if-exists --no-owner --no-acl \
    --single-transaction --exit-on-error "$2"
' sh "$database" "$staged" || die "pg_restore failed; the target database was left unchanged"

accounts="$(docker exec "$container" sh -c 'psql -U "${POSTGRES_USER:-postgres}" -d "$1" -tAc "SELECT count(*) FROM account"' sh "$database")"
echo "restore: dump restored in $((SECONDS - started)) s; account rows: $accounts"

if [[ "$skip_replay" == true ]]; then
  echo "restore: WARNING --skip-replay: accounts erased and sessions revoked after this dump was taken are live" >&2
  echo "restore: WARNING run scripts/backup/post-restore.sh before the backend serves traffic" >&2
  exit 0
fi
"$here/post-restore.sh" --container "$container" --database "$database" ${ledger_args[@]+"${ledger_args[@]}"} \
  || die "the dump was restored but the post-restore step FAILED: do not start the backend; fix the cause and run scripts/backup/post-restore.sh"
echo "restore: complete in $((SECONDS - started)) s (dump restored, erasure ledger replayed, sessions revoked)"
