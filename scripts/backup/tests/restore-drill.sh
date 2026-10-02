#!/usr/bin/env bash
# Restore drill for the erasure ledger (AC-09) against a throwaway PostgreSQL container. Needs only the Docker CLI;
# works in Git Bash and Linux shells. Never touches other containers: it starts its own container without a network
# and without published ports, and removes it (with its volume) on exit.
#
#   scripts/backup/tests/restore-drill.sh
#
# 1. Builds the schema from src/main/resources/db/migration (version order) and the runtime role
#    (infra/postgres/app-role.sql), adds two accounts with live refresh tokens and takes a pg_dump ("the backup").
# 2. After the backup: purges account A the way AccountPurger does in the database (rows deleted, a ledger line
#    appended to the ledger file outside the dump) and logs account B out (its token revoked).
# 3. Restores the OLD dump with scripts/backup/restore.sh (which runs post-restore.sh) and checks: the ledger entry
#    is back in erasure_ledger, a replay is pending for the backend (which purges A again at startup:
#    ErasureLedgerRestoreIT), no refresh token is live, every session epoch moved, the runtime role still has DML.
# 4. Checks that restore.sh refuses to run without a ledger source, before anything is dropped.
# Exit 0 when every check passed.
set -euo pipefail
export MSYS_NO_PATHCONV=1

root="$(cd "$(dirname "$0")/../../.." && pwd)"
IMAGE="${DRILL_IMAGE:-postgres:15-alpine@sha256:f7d23353e1b15400d22ebe31189f4d314b87a4c129cc400c8c2d8d4ca127bf81}"
name="educore-restore-drill-$$"
work="$(mktemp -d)"
db="drill_db"
owner="drill_owner"
app="drill_app"

cleanup() {
  docker rm -fv "$name" > /dev/null 2>&1 || true
  rm -rf -- "$work"
}
trap cleanup EXIT

fail() { echo "restore-drill: FAIL $*" >&2; exit 1; }
pass() { echo "restore-drill: ok   $*"; }
psql_owner() { docker exec -i "$name" psql -X -q -v ON_ERROR_STOP=1 -U "$owner" -d "$db"; }
scalar() { docker exec "$name" psql -X -tA -U "$owner" -d "$db" -c "$1"; }

# Throwaway passwords (local socket and a container without network; never used anywhere else).
owner_password="drill-$(date +%s)-$RANDOM-owner"
app_password="drill-$(date +%s)-$RANDOM-app"

docker run -d --name "$name" --network none \
  -e POSTGRES_USER="$owner" -e POSTGRES_PASSWORD="$owner_password" -e POSTGRES_DB="$db" \
  -e EDUCORE_DB_APP_USERNAME="$app" -e EDUCORE_DB_APP_PASSWORD="$app_password" \
  "$IMAGE" > /dev/null
for _ in $(seq 1 60); do
  if docker exec "$name" pg_isready -U "$owner" -d "$db" > /dev/null 2>&1 \
     && docker exec "$name" psql -X -tA -U "$owner" -d "$db" -c "SELECT 1" > /dev/null 2>&1; then
    break
  fi
  sleep 1
done
scalar "SELECT 1" > /dev/null || fail "throwaway PostgreSQL did not start"

# ---- 1. schema, runtime role, data, backup --------------------------------------------------------------------
mapfile -t migrations < <(find "$root/src/main/resources/db/migration" -maxdepth 1 -name "V*__*.sql" -printf '%f\n' | sort -t_ -k1.2 -n)
for migration in "${migrations[@]}"; do
  psql_owner < "$root/src/main/resources/db/migration/$migration" > /dev/null || fail "migration $migration"
done
pass "schema built from ${#migrations[@]} migrations"

docker exec "$name" mkdir -p /opt/educore
docker exec -i "$name" sh -c 'cat > /opt/educore/app-role.sql' < "$root/infra/postgres/app-role.sql"
docker exec "$name" sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v app_user="$EDUCORE_DB_APP_USERNAME" -v owner="$POSTGRES_USER" -v db="$POSTGRES_DB" -f /opt/educore/app-role.sql' \
  || fail "runtime role"
pass "runtime role created"

psql_owner > /dev/null <<'SQL'
INSERT INTO account (id, username, password, first_name, last_name, student_number, role, status, session_epoch)
VALUES (91001, 'drill-a', 'drill-hash', 'Drill', 'Erased', '99000001', 'USER', 'ACTIVE', 0),
       (91002, 'drill-b', 'drill-hash', 'Drill', 'Kept', '99000002', 'USER', 'ACTIVE', 0);
INSERT INTO refresh_token_family (id, account_id, created_at)
VALUES ('00000000-0000-0000-0000-00000000000a', 91001, now()),
       ('00000000-0000-0000-0000-00000000000b', 91002, now());
INSERT INTO refresh_token (account_id, token_hash, family_id, issued_at, expires_at)
VALUES (91001, repeat('a', 64), '00000000-0000-0000-0000-00000000000a', now(), now() + interval '14 days'),
       (91002, repeat('b', 64), '00000000-0000-0000-0000-00000000000b', now(), now() + interval '14 days');
SQL
docker exec "$name" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --compress=0 | gzip -6' \
  > "$work/old.dump.gz"
pass "backup taken ($(wc -c < "$work/old.dump.gz") bytes)"

# ---- 2. after the backup: purge A (database + ledger file), log B out -------------------------------------------
digest() { printf '%s' "$1" | sha256sum | cut -c1-64; }
ledger="$work/erasure-ledger.log"
line="v1 $(date -u +%Y-%m-%dT%H:%M:%S.000000Z) $(digest drill-account-91001) $(digest drill-username-a) $(digest drill-student-99000001)"
printf '%s\n' "$line" >> "$ledger"
psql_owner > /dev/null <<SQL
DELETE FROM refresh_token WHERE account_id = 91001;
DELETE FROM refresh_token_family WHERE account_id = 91001;
DELETE FROM account WHERE id = 91001;
INSERT INTO erasure_ledger (account_digest, username_digest, student_number_digest, purged_at)
VALUES ('$(digest drill-account-91001)', '$(digest drill-username-a)', '$(digest drill-student-99000001)', now());
UPDATE refresh_token SET revoked_at = now() WHERE account_id = 91002;
SQL
[[ "$(scalar "SELECT count(*) FROM account WHERE id = 91001")" == "0" ]] || fail "account A was not purged"
pass "account A purged and ledgered, account B logged out"

# ---- 4 (first: nothing is dropped). restore.sh refuses without a ledger source -----------------------------------
if out="$("$root/scripts/backup/restore.sh" --container "$name" --ledger-volume "educore-drill-missing-$$" --yes \
      "$work/old.dump.gz" 2>&1)"; then
  fail "restore.sh accepted a missing ledger volume"
fi
[[ "$out" == *"erasure ledger volume"*"not found"* ]] || fail "unexpected refusal message: $out"
[[ "$(scalar "SELECT count(*) FROM account WHERE id = 91001")" == "0" ]] || fail "refused restore changed the database"
pass "restore.sh refuses a restore without a ledger source and leaves the database unchanged"

# ---- 3. restore the OLD dump, post-restore runs ------------------------------------------------------------------
"$root/scripts/backup/restore.sh" --container "$name" --ledger-file "$ledger" --yes "$work/old.dump.gz" \
  | sed 's/^/    /'

[[ "$(scalar "SELECT count(*) FROM account WHERE id = 91001")" == "1" ]] \
  || fail "the old dump should bring account A back (the backend purges it at startup)"
[[ "$(scalar "SELECT count(*) FROM erasure_ledger WHERE account_digest = '$(digest drill-account-91001)'")" == "1" ]] \
  || fail "ledger entry not replayed into erasure_ledger"
pass "erasure ledger entry replayed into the restored database"
[[ "$(scalar "SELECT count(*) FROM restore_replay WHERE completed_at IS NULL AND source = 'POST_RESTORE'")" == "1" ]] \
  || fail "no pending replay for the backend"
pass "backend replay pending (purges account A before serving)"
[[ "$(scalar "SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL")" == "0" ]] \
  || fail "a refresh token is live after the restore"
[[ "$(scalar "SELECT count(*) FROM refresh_token_family WHERE revoked_at IS NULL")" == "0" ]] \
  || fail "a refresh token family is live after the restore"
pass "every refresh token and family revoked (B's logout holds again)"
[[ "$(scalar "SELECT min(session_epoch) FROM account WHERE id IN (91001, 91002)")" == "1" ]] \
  || fail "session epochs were not incremented"
pass "every session epoch incremented"
app_rows="$(docker exec -e PGPASSWORD="$app_password" "$name" psql -X -tA -h 127.0.0.1 -U "$app" -d "$db" \
  -c "SELECT count(*) FROM account")" || fail "runtime role lost its privileges after the restore"
[[ "$app_rows" == "2" ]] || fail "runtime role sees $app_rows accounts"
if docker exec -e PGPASSWORD="$app_password" "$name" psql -X -tA -h 127.0.0.1 -U "$app" -d "$db" \
     -c "DROP TABLE enrollments" > /dev/null 2>&1; then
  fail "runtime role could drop a table"
fi
pass "runtime role keeps DML after the restore and still cannot DROP"

echo "restore-drill: RESULT OK"
