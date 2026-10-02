#!/usr/bin/env bash
# Restore the newest EduCore backup into a throwaway PostgreSQL container, check that the schema and
# data are really there, and report the RPO age of that backup.
#
# Usage:
#   scripts/backup/verify-latest.sh [--volume NAME | --dir PATH | --file PATH] [--max-age-hours N]
#                                   [--require-tables LIST] [--min-accounts N]
#
#   --volume NAME       Docker volume holding the sidecar's /backups tree (default: educore_backups).
#   --dir PATH          Host directory with the same layout (daily/educore-*.dump.gz).
#   --file PATH         Verify one specific dump file (.dump.gz or .dump) instead of the newest one.
#   --max-age-hours N   RPO target in hours (default: 24). An older backup makes the script fail.
#   --require-tables L  Comma-separated tables that must exist after the restore
#                       (default: account,course,enrollments,flyway_schema_history).
#   --min-accounts N    Minimum rows in account (default: 1). A schema-only or emptied dump fails.
#                       When flyway_schema_history is required it must hold at least one successful migration.
#
# Environment: BACKUP_VERIFY_IMAGE overrides the PostgreSQL image (default pinned postgres:15-alpine).
# Exit codes: 0 verified and within RPO, 1 usage, restore or content-check failure, 3 restored and
# checked but older than the RPO target.
# Requires only a Docker CLI; works in Git Bash on Windows and in Linux shells. The throwaway
# container has no network and is removed on exit; it never touches other containers.
set -euo pipefail

# Git Bash would otherwise rewrite container paths such as /backups into Windows paths.
export MSYS_NO_PATHCONV=1

IMAGE="${BACKUP_VERIFY_IMAGE:-postgres:15-alpine@sha256:f7d23353e1b15400d22ebe31189f4d314b87a4c129cc400c8c2d8d4ca127bf81}"
volume="educore_backups"
dir=""
file=""
max_age_hours=24
require_tables="account,course,enrollments,flyway_schema_history"
min_accounts=1

usage() { sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; }
die() { echo "verify-latest: $*" >&2; exit 1; }

# Absolute host path in a form the Docker CLI accepts (C:/... in Git Bash, /... elsewhere).
host_path() {
  (cd "$1" && { pwd -W 2>/dev/null || pwd; })
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --volume) volume="${2:?--volume needs a value}"; shift 2 ;;
    --dir) dir="${2:?--dir needs a value}"; volume=""; shift 2 ;;
    --file) file="${2:?--file needs a value}"; volume=""; shift 2 ;;
    --max-age-hours) max_age_hours="${2:?--max-age-hours needs a value}"; shift 2 ;;
    --require-tables) require_tables="${2:?--require-tables needs a value}"; shift 2 ;;
    --min-accounts) min_accounts="${2:?--min-accounts needs a value}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 1 ;;
  esac
done
[[ "$max_age_hours" =~ ^[1-9][0-9]*$ ]] || die "--max-age-hours must be a positive integer"
[[ "$min_accounts" =~ ^[0-9]+$ ]] || die "--min-accounts must be a non-negative integer"
IFS=',' read -r -a tables <<< "$require_tables"
for t in "${tables[@]}"; do
  [[ "$t" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || die "invalid table name in --require-tables: '$t'"
done

command -v docker > /dev/null 2>&1 || die "docker CLI not found"

if [[ -n "$file" ]]; then
  [[ -f "$file" ]] || die "file not found: $file"
  mount="$(host_path "$(dirname "$file")"):/backups:ro"
  target="/backups/$(basename "$file")"
elif [[ -n "$dir" ]]; then
  [[ -d "$dir" ]] || die "directory not found: $dir"
  mount="$(host_path "$dir"):/backups:ro"
  target=""
else
  docker volume inspect "$volume" > /dev/null 2>&1 || die "docker volume not found: $volume"
  mount="$volume:/backups:ro"
  target=""
fi

name="educore-verify-$$-${RANDOM}"
# -v also removes the container's anonymous data volume (the restored copy of the database).
cleanup() { docker rm -fv "$name" > /dev/null 2>&1 || true; }
trap cleanup EXIT

started=$SECONDS
# trust auth is safe here: --network none, so only `docker exec` can reach the server.
docker run -d --name "$name" --network none \
  --label educore.purpose=backup-verify \
  -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=verify \
  -v "$mount" "$IMAGE" > /dev/null

# The image's init phase runs a socket-only server; TCP readiness means the final server is up.
ready=false
for _ in $(seq 1 60); do
  if docker exec "$name" pg_isready -h 127.0.0.1 -U postgres -d verify > /dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
[[ "$ready" == true ]] || die "throwaway PostgreSQL did not become ready within 60 s"

if [[ -z "$target" ]]; then
  target="$(docker exec "$name" sh -c 'ls -1 /backups/daily/educore-*.dump.gz 2>/dev/null | sort | tail -n 1')"
  [[ -n "$target" ]] || die "no backups found under daily/ in the source"
fi
echo "verify-latest: backup file   $target"

# Checksum sidecar written by the backup job, when present.
if docker exec "$name" test -f "$target.sha256"; then
  docker exec -w "$(dirname "$target")" "$name" sha256sum -c "$(basename "$target").sha256" > /dev/null \
    || die "checksum mismatch for $target"
  echo "verify-latest: checksum      OK"
fi

# gzip integrity is checked on its own first; pipefail then makes a failure of any pipeline stage fatal.
docker exec "$name" sh -c '
  set -eu
  set -o pipefail
  case "$1" in
    *.gz) gzip -t "$1" ;;
  esac
  case "$1" in
    *.gz) gunzip -c "$1" ;;
    *) cat "$1" ;;
  esac | pg_restore --no-owner --no-acl --exit-on-error -U postgres -d verify
' sh "$target" || die "gzip check or pg_restore failed for $target"

sql() { docker exec "$name" psql -U postgres -d verify -v ON_ERROR_STOP=1 -tAc "$1"; }

for t in "${tables[@]}"; do
  [[ "$(sql "SELECT to_regclass('public.$t') IS NOT NULL")" == "t" ]] || die "required table '$t' is missing after the restore"
done
echo "verify-latest: tables        present: ${require_tables}"

if [[ ",${require_tables}," == *",flyway_schema_history,"* ]]; then
  migrations="$(sql 'SELECT count(*) FROM flyway_schema_history WHERE success')" || die "flyway_schema_history query failed"
  (( migrations >= 1 )) || die "flyway_schema_history has no successful migration"
  echo "verify-latest: migrations    $migrations successful"
fi

accounts="$(sql 'SELECT count(*) FROM account')" || die "SELECT count(*) FROM account failed"
echo "verify-latest: account rows  $accounts (minimum $min_accounts)"
(( accounts >= min_accounts )) || die "account has $accounts rows, expected at least $min_accounts (schema-only or emptied dump?)"
echo "verify-latest: restore took  $((SECONDS - started)) s (container start + restore)"

# Backup time: the UTC timestamp embedded in the file name, else the file's modification time.
read -r taken now < <(docker exec "$name" sh -c '
  base=$(basename "$1")
  ts=$(printf "%s" "$base" | sed -n "s/^educore-\([0-9]\{8\}\)T\([0-9]\{6\}\)Z.*/\1\2/p")
  if [ -n "$ts" ]; then
    taken=$(date -u -d "$(printf "%s" "$ts" | sed "s/^\(....\)\(..\)\(..\)\(..\)\(..\)\(..\)$/\1-\2-\3 \4:\5:\6/")" +%s)
  else
    taken=$(stat -c %Y "$1")
  fi
  echo "$taken $(date -u +%s)"
' sh "$target")

age=$((now - taken))
(( age < 0 )) && age=0
printf 'verify-latest: backup taken  %s UTC\n' "$(docker exec "$name" date -u -d "@$taken" '+%Y-%m-%d %H:%M:%S')"
printf 'verify-latest: RPO age       %dh %02dm (target %dh)\n' $((age / 3600)) $(((age % 3600) / 60)) "$max_age_hours"

if (( age > max_age_hours * 3600 )); then
  echo "verify-latest: RESULT        RESTORED, BUT RPO TARGET BREACHED" >&2
  exit 3
fi
echo "verify-latest: RESULT        OK"
