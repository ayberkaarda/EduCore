#!/usr/bin/env bash
# Post-restore step (AC-09): makes a restored EduCore database safe to serve again. scripts/backup/restore.sh runs
# it automatically after a successful restore; run it by hand after any other kind of restore, BEFORE the backend
# starts.
#
# Usage:
#   scripts/backup/post-restore.sh [--container NAME] [--database DB]
#                                  [--ledger-volume NAME | --ledger-file PATH | --no-ledger]
#
#   --container NAME      database container (default: educore-postgres)
#   --database DB         database (default: the container's POSTGRES_DB)
#   --ledger-volume NAME  Docker volume holding erasure-ledger.log (default: educore_erasure_ledger)
#   --ledger-file PATH    read the ledger from a host file instead (e.g. the newest ledger/ copy of a backup)
#   --no-ledger           no ledger exists (nothing was ever purged); sessions are still reset
#
# In one transaction it re-inserts every ledger entry into erasure_ledger (the dump rewound that table, the ledger
# volume is never part of a dump), revokes every refresh token and family, increments every account's session
# epoch and records a pending restore_replay. When the backend starts, it purges each restored account the ledger
# lists (it holds the pepper the digests are keyed with) before it accepts a request. Finally the runtime role's
# privileges are re-applied (infra/postgres/app-role.sql) when the container defines EDUCORE_DB_APP_USERNAME.
# Ledger lines are validated strictly (version, timestamp, hex digests) before they reach SQL.
set -euo pipefail
umask 077
export MSYS_NO_PATHCONV=1

container="educore-postgres"
database=""
ledger_volume="educore_erasure_ledger"
ledger_file=""
no_ledger=false
here="$(cd "$(dirname "$0")" && pwd)"

usage() { sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; }
die() { echo "post-restore: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --container) container="${2:?--container needs a value}"; shift 2 ;;
    --database) database="${2:?--database needs a value}"; shift 2 ;;
    --ledger-volume) ledger_volume="${2:?--ledger-volume needs a value}"; ledger_file=""; shift 2 ;;
    --ledger-file) ledger_file="${2:?--ledger-file needs a value}"; shift 2 ;;
    --no-ledger) no_ledger=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 1 ;;
  esac
done

command -v docker > /dev/null 2>&1 || die "docker CLI not found"
[[ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" == "true" ]] \
  || die "container '$container' is not running"
if [[ -z "$database" ]]; then
  database="$(docker exec "$container" sh -c 'printf "%s" "${POSTGRES_DB:-postgres}"')"
fi
[[ -f "$here/post-restore.sql" ]] || die "post-restore.sql not found next to this script"

# ---- read the ledger ------------------------------------------------------------------------------------------
ledger=""
if [[ "$no_ledger" == true ]]; then
  echo "post-restore: --no-ledger: no erasure entries are replayed"
elif [[ -n "$ledger_file" ]]; then
  [[ -r "$ledger_file" ]] || die "ledger file not readable: $ledger_file"
  ledger="$(cat -- "$ledger_file")"
else
  docker volume inspect "$ledger_volume" > /dev/null 2>&1 \
    || die "erasure ledger volume '$ledger_volume' not found; pass --ledger-file PATH (a ledger/ copy from the backup) or --no-ledger if nothing was ever purged"
  # Read with the database container's own image (already present locally), without network.
  image="$(docker inspect -f '{{.Config.Image}}' "$container")"
  ledger="$(docker run --rm --network none -v "$ledger_volume:/ledger:ro" --entrypoint sh "$image" \
    -c 'if [ -f /ledger/erasure-ledger.log ]; then cat /ledger/erasure-ledger.log; fi')" \
    || die "could not read the erasure ledger from volume '$ledger_volume'"
fi

line_re='^v1 ([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z) ([0-9a-f]{64}) ([0-9a-f]{64}) ([0-9a-f]{64}|-)$'
values=()
invalid=0
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  [[ -z "$line" ]] && continue
  if [[ "$line" =~ $line_re ]]; then
    student="NULL"
    [[ "${BASH_REMATCH[5]}" != "-" ]] && student="'${BASH_REMATCH[5]}'"
    values+=("('${BASH_REMATCH[3]}', '${BASH_REMATCH[4]}', $student, '${BASH_REMATCH[1]}'::timestamptz)")
  else
    invalid=$((invalid + 1))
  fi
done <<< "$ledger"
(( invalid == 0 )) || die "the erasure ledger has $invalid malformed line(s); repair it (or pass a backup copy with --ledger-file) before serving"

# ---- one transaction: ledger entries, session reset, replay request --------------------------------------------
sql_file="$(mktemp)"
trap 'rm -f -- "$sql_file"' EXIT
{
  echo "\\set ON_ERROR_STOP on"
  echo "BEGIN;"
  if (( ${#values[@]} > 0 )); then
    echo "INSERT INTO erasure_ledger (account_digest, username_digest, student_number_digest, purged_at) VALUES"
    for i in "${!values[@]}"; do
      if (( i + 1 < ${#values[@]} )); then echo "  ${values[$i]},"; else echo "  ${values[$i]}"; fi
    done
    echo "ON CONFLICT (account_digest) DO NOTHING;"
  fi
  cat -- "$here/post-restore.sql"
  echo "COMMIT;"
} > "$sql_file"

docker exec -i "$container" sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-postgres}" -d "$1"' \
  sh "$database" < "$sql_file" > /dev/null || die "post-restore SQL failed; the database is unchanged by this step"

# ---- runtime role privileges ------------------------------------------------------------------------------------
# pg_restore --no-acl recreates tables owned by the restoring owner; the owner's default privileges normally grant
# the runtime role again, and re-applying the role script makes sure (idempotent).
if docker exec "$container" sh -c '[ -n "${EDUCORE_DB_APP_USERNAME:-}" ] && [ -f /opt/educore/app-role.sql ]'; then
  docker exec "$container" sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-postgres}" -d "$1" \
      -v app_user="$EDUCORE_DB_APP_USERNAME" -v owner="${POSTGRES_USER:-postgres}" -v db="$1" \
      -f /opt/educore/app-role.sql' sh "$database" > /dev/null \
    || die "runtime role privileges could not be re-applied"
  echo "post-restore: runtime role privileges re-applied"
fi

summary="$(docker exec "$container" sh -c 'psql -X -tA -F " " -U "${POSTGRES_USER:-postgres}" -d "$1" -c "
  SELECT (SELECT count(*) FROM erasure_ledger),
         (SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL),
         (SELECT count(*) FROM restore_replay WHERE completed_at IS NULL)"' sh "$database")"
read -r ledger_rows live_tokens pending <<< "$summary"
echo "post-restore: ledger entries replayed from the volume/file: ${#values[@]} (table now holds $ledger_rows)"
echo "post-restore: live refresh tokens: $live_tokens; pending erasure replays: $pending"
[[ "$live_tokens" == "0" && "$pending" -ge 1 ]] || die "post-restore verification failed"
echo "post-restore: OK. Start the backend: it purges the ledger's accounts again before it serves a request."
