# Upgrade Notes

## P5 / P7 / security fixes: one-time out-of-order Flyway migration (V12, V13, V21, V22, V23, V33, V34)

Seven new migrations have version numbers that sort **below** migrations released earlier (V30–V32, V40–V42):

| Migration | Phase | Change |
|---|---|---|
| `V12__ip_access.sql` | P5 | renames `ip_block` to `ip_allocation_range` (rows kept), creates `ip_deny_rule` |
| `V13__ip_deny_auto_unique.sql` | P5 | one automatic deny rule per address (unique partial index) |
| `V21__account_lifecycle.sql` | P7 | `account.deleted` becomes `account.status` (`deleted = 0` → `ACTIVE`, otherwise `DEACTIVATED`), adds `deleted_at`/`delete_after` and audit pseudonym columns, drops `deleted` |
| `V22__account_session_epoch.sql` | security fixes | `account.session_epoch` (default 0): access tokens of earlier lifecycle states stop at once |
| `V23__login_attempt_client_key.sql` | security fixes | `login_attempt.client_key` (backfilled from `ip`) and an index for the per-(username, network) lockout |
| `V33__erasure_ledger.sql` | security fixes (2) | `erasure_ledger` (keyed digests of purged accounts) and `restore_replay` (pending post-restore replays) |
| `V34__upload_staging.sql` | security fixes (2) | `upload_staging`: owner, lease and state of manual uploads waiting in `staging/` |

Flyway therefore treats all of them as out of order on every database that was migrated before them. They are
independent of each other and of V30–V42, so applying them after those is safe.

| Database | What happens on the first P5 start |
|---|---|
| New, empty | Nothing special: V1 … V42 run in order. |
| Migrated before P5/P7 | Startup stops: `Validate failed: Migrations have failed validation` / `Detected resolved migration not applied to database: 12` (and `13`, `21`, `22`, `23`, `33`, `34`; a database that already has some of them lists only the missing ones). No change is made to the schema. |

Upgrade procedure for such a database (tested by `FlywayOutOfOrderUpgradeIT`, which builds a database with
every released migration, an `ip_block` row and one active plus one soft-deleted account):

1. Take a backup first (`docs/ops/BACKUP_RESTORE.md`).
2. Before the upgrade, record the current state:

   ```sql
   SELECT count(*) AS rows, max(id) AS max_id FROM ip_block;
   SELECT pg_get_serial_sequence('ip_block', 'id') AS id_sequence;
   SELECT deleted, count(*) FROM account GROUP BY deleted;
   SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;
   ```

   Every row of the history must have `success = true`.
3. Start the new version **once** with `SPRING_FLYWAY_OUT_OF_ORDER=true` in the backend environment. The
   compose file passes only listed variables, so use a one-off container:

   ```sh
   docker compose stop educore-backend
   docker compose run --rm -e SPRING_FLYWAY_OUT_OF_ORDER=true educore-backend
   # wait for "Started EduCoreApplication", then stop it with Ctrl+C
   ```

   Flyway applies V12, V13, V21, V22, V23, V33 and V34 (whichever are missing) and records them as out-of-order; Hibernate's `ddl-auto=validate` then
   checks the new schema. Outside compose, export the variable for that one start only.
4. Verify:

   ```sql
   SELECT count(*) AS rows, max(id) AS max_id FROM ip_allocation_range;   -- same numbers as in step 2
   SELECT to_regclass('ip_block');                                         -- NULL: the old name is gone
   SELECT pg_get_serial_sequence('ip_allocation_range', 'id');             -- the identity sequence moved with the table
   SELECT status, count(*) FROM account GROUP BY status;    -- ACTIVE = former deleted 0, DEACTIVATED = the rest
   SELECT version, type, success FROM flyway_schema_history WHERE version IN ('12', '13', '21', '22', '23', '33', '34');  -- all true
   ```

   A new allocation range created through `POST /api/v1/admin/ip-allocations` must get an id above `max_id`.
5. Start the service normally (`docker compose up -d educore-backend`), without the variable. Later starts
   validate normally; keeping the flag would silently accept any future migration that is numbered wrongly.

Do not set `spring.flyway.out-of-order` permanently in `application*.yml`. Rollback: restore the backup from
step 1 (the rename is not reversed automatically; D-09 in `docs/DECISIONS_TAKEN.md` lists the manual reversal).

## Promoting a database that was used under dev

A database that was ever started with the `dev` (or `test`) profile contains the synthetic accounts of
`db/seed/dev/R__dev_seed.sql` (`admin`, `ayberk`, `ali`), whose demo password is public. Switching such a database
to `prod` does not remove them, and the bootstrap ADMIN is not created while an ADMIN exists. Since the security
fixes the `prod` profile therefore refuses to start (`DevSeedAccountGuard`, R-02) with:

```
Refusing to start with profile 'prod': the database contains the development seed account(s) admin, ayberk, ali
from db/seed/dev/R__dev_seed.sql (their demo password is public). Remove them before using this database in
production: docs/ops/UPGRADE.md, section "Promoting a database that was used under dev".
```

An account counts as a seed account when its username is a seeded one and it still has the seeded password hash
or the seeded student number (900000x), also after its password was re-hashed by a login. Remedy (take a backup
first, `docs/ops/BACKUP_RESTORE.md`):

```sql
BEGIN;
DELETE FROM enrollments WHERE account_id IN (SELECT id FROM account WHERE username IN ('admin', 'ayberk', 'ali'));
DELETE FROM account WHERE username IN ('admin', 'ayberk', 'ali');   -- refresh tokens and families cascade
COMMIT;
```

Then start `prod` with `EDUCORE_BOOTSTRAP_ADMIN_USERNAME` / `EDUCORE_BOOTSTRAP_ADMIN_PASSWORD` set: with no ADMIN
left, the bootstrap ADMIN is created and must change its password at the first sign-in. The seed courses are
harmless and may stay. Do not rename the seed accounts instead of deleting them: they would keep the public
password. `FlywayProfileSwitchIT` covers the refusal and the start after the remedy.

## Least-privilege database role (security fixes 2, AC-15)

The backend no longer connects as the database owner. `POSTGRES_USER` (`EDUCORE_DB_USERNAME`) stays the bootstrap superuser
and schema owner: Flyway migrates as it (`EDUCORE_DB_MIGRATION_USERNAME` / `_PASSWORD`, set by compose from
`EDUCORE_DB_USERNAME` / `EDUCORE_DB_PASSWORD`) and the backup sidecar dumps as it. The connection pool uses the runtime role
`EDUCORE_DB_APP_USERNAME` / `EDUCORE_DB_APP_PASSWORD`, which `infra/postgres/app-role.sql` grants CONNECT, USAGE on schema
`public`, SELECT/INSERT/UPDATE/DELETE on the tables and USAGE/SELECT/UPDATE on the sequences, now and (default privileges) for
every table a later migration creates. It cannot DROP/ALTER/TRUNCATE tables, create objects or extensions, run
`COPY ... PROGRAM`, read server files or `pg_authid` (`DatabaseRolesIT`). The `prod` profile refuses to start without the
migration role or when both roles are the same (`ProdStartupGuard`).

A **new** database gets the role on its first start (`infra/postgres/init/01-roles.sh`, mounted into
`/docker-entrypoint-initdb.d`). An **existing** database (the `pgdata` volume already initialised) does not run init scripts
again; create the role once:

1. Take a backup (`docs/ops/BACKUP_RESTORE.md`).
2. Add to `.env` (different name and password from the owner; lower-case letters, digits, underscores):

   ```sh
   EDUCORE_DB_APP_USERNAME=educore_app
   EDUCORE_DB_APP_PASSWORD=<openssl rand -base64 24>
   ```
3. Recreate the database container with the new environment and mounts (the data volume is kept), create the role, start the
   backend:

   ```sh
   docker compose stop educore-backend
   docker compose up -d postgres-db
   docker compose exec postgres-db bash /docker-entrypoint-initdb.d/01-roles.sh
   docker compose up -d educore-backend
   ```

   The script is idempotent: run it again after changing `EDUCORE_DB_APP_PASSWORD` (it updates the password).
4. Verify (as the owner, e.g. `docker compose exec postgres-db psql -U "$EDUCORE_DB_USERNAME" -d "$EDUCORE_DB_NAME"`):

   ```sql
   SELECT rolname, rolsuper, rolcreaterole, rolcreatedb FROM pg_roles WHERE rolname = 'educore_app';  -- f, f, f
   SELECT usename, count(*) FROM pg_stat_activity WHERE datname = current_database() GROUP BY usename;  -- pool = educore_app
   SELECT DISTINCT installed_by FROM flyway_schema_history;  -- the owner only
   ```

Outside compose (another PostgreSQL), run the same script with psql as the owner; the password comes from the environment:

```sh
EDUCORE_DB_APP_PASSWORD='<password>' psql -v ON_ERROR_STOP=1 -U <owner> -d <database> \
  -v app_user=educore_app -v owner=<owner> -v db=<database> -f infra/postgres/app-role.sql
```

or, as plain SQL (replace the names; `<owner>` is the role that runs the migrations):

```sql
CREATE ROLE educore_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '<password>';
REVOKE ALL ON DATABASE educore_db FROM PUBLIC;
GRANT CONNECT ON DATABASE educore_db TO educore_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO educore_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO educore_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO educore_app;
ALTER DEFAULT PRIVILEGES FOR ROLE <owner> IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO educore_app;
ALTER DEFAULT PRIVILEGES FOR ROLE <owner> IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO educore_app;
```

and start the backend with `EDUCORE_DB_APP_USERNAME`/`_PASSWORD` for the pool and `EDUCORE_DB_MIGRATION_USERNAME`/`_PASSWORD`
set to the owner. Rollback: set both back to the owner outside `prod` (or drop the role after `REASSIGN OWNED`; it owns nothing).

## Erasure ledger and processed CSV files (security fixes 2, AC-08/AC-09)

- The backend image creates `/var/lib/educore`, and compose mounts the new volume `educore_erasure_ledger` there
  (`EDUCORE_ERASURE_LEDGER_FILE=/var/lib/educore/erasure-ledger.log`, required in `prod`). Rebuild the backend image before the
  first start. Outside compose, point `EDUCORE_ERASURE_LEDGER_FILE` at a file on storage that is **not** restored together
  with the database.
- Purges made before this version are not in the ledger (their ids are gone). Until the dumps taken before the upgrade have
  rotated out (about 56 days), follow the extra steps in `docs/ops/DATA_RETENTION.md` ("Backups") after a restore.
- The first hourly ingestion cleanup after the upgrade deletes every file in `csv_uploads/done/` (the default
  `educore.ingestion.retain-processed-days` is 0) and every file in `csv_uploads/failed/` older than 7 days. Copy out anything
  you still need before upgrading, or raise the retention for the transition.
- The backup sidecar stops archiving `csv_uploads/done` (`BACKUP_ARCHIVE_CSV=false`); existing `csv-done/` archives age out
  after `BACKUP_KEEP_CSV` days. It now copies the erasure ledger into `ledger/`.
