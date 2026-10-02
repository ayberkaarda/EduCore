#!/usr/bin/env bash
# Entrypoint of the backup sidecar.
#   schedule    (default) run backup.sh on BACKUP_SCHEDULE [0 2 * * *] via supercronic; with
#               BACKUP_RUN_ON_START=true one backup runs immediately before the schedule starts.
#   backup-now  run one backup and exit (used by drills and CI).
#
# The database password is read from BACKUP_DB_PASSWORD_FILE [/run/secrets/educore_db_password] (a Docker
# secret) and written to a private libpq password file (mode 0600, PGPASSFILE), so it never appears in the
# environment of the backup processes.
set -euo pipefail
umask 077

mode="${1:-schedule}"

die() { echo "backup: $*" >&2; exit 1; }

# libpq .pgpass fields escape backslash and colon.
pgpass_field() { printf '%s' "$1" | sed 's/[\\:]/\\&/g'; }

prepare_credentials() {
  [[ -z "${PGPASSWORD:-}" ]] || die "PGPASSWORD must not be set; mount the password as a file (BACKUP_DB_PASSWORD_FILE)"
  local pwfile="${BACKUP_DB_PASSWORD_FILE:-/run/secrets/educore_db_password}" password pgpass
  [[ -r "$pwfile" ]] || die "database password file '$pwfile' is missing or unreadable"
  : "${PGHOST:?PGHOST is required}" "${PGUSER:?PGUSER is required}" "${PGDATABASE:?PGDATABASE is required}"
  password="$(< "$pwfile")"
  [[ -n "$password" ]] || die "database password file '$pwfile' is empty"
  pgpass="$(mktemp /tmp/educore-pgpass.XXXXXX)"
  printf '%s:%s:%s:%s:%s\n' "$(pgpass_field "$PGHOST")" "$(pgpass_field "${PGPORT:-5432}")" \
    "$(pgpass_field "$PGDATABASE")" "$(pgpass_field "$PGUSER")" "$(pgpass_field "$password")" > "$pgpass"
  unset password
  export PGPASSFILE="$pgpass"
}

prepare_credentials
# Reference time for the healthcheck before the first scheduled run (backup-health.sh).
date -u +%s > /tmp/educore-backup-started

case "$mode" in
  backup-now)
    exec /usr/local/bin/backup.sh
    ;;
  schedule)
    schedule="${BACKUP_SCHEDULE:-0 2 * * *}"
    # Five cron fields: minute hour day-of-month month day-of-week.
    read -r -a fields <<< "$schedule"
    [[ ${#fields[@]} -eq 5 ]] || die "BACKUP_SCHEDULE must have exactly five cron fields (got '$schedule')"
    crontab="$(mktemp /tmp/educore-backup-crontab.XXXXXX)"
    printf '%s /usr/local/bin/backup.sh\n' "$schedule" > "$crontab"
    if [[ "${BACKUP_RUN_ON_START:-false}" == "true" ]]; then
      /usr/local/bin/backup.sh
    fi
    echo "backup: schedule '$schedule' (TZ=${TZ:-UTC})"
    exec supercronic -passthrough-logs "$crontab"
    ;;
  *)
    echo "backup: unknown mode '$mode' (expected: schedule | backup-now)" >&2
    exit 64
    ;;
esac
