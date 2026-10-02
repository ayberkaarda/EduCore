# Backup and Restore

Security checklist item 20 (scheduled backups with a tested restore).

## Targets

| Objective | Target | How it is met |
|---|---|---|
| RPO (maximum data loss) | 24 h | Daily `pg_dump` at 02:00 (`BACKUP_SCHEDULE`); `verify-latest.sh` fails when the newest dump is older than 24 h. |
| RTO (time to service) | 1 h | `restore.sh` restores in a single transaction; the nightly CI drill and the quarterly drill measure the real duration. |

## Components

| Path | Purpose |
|---|---|
| `infra/backup/Dockerfile` | Sidecar image: `postgres:15-alpine` (pinned digest) + `supercronic` + `rclone`; runs as uid 70, never root. |
| `infra/backup/bin/backup.sh` | One run: `pg_dump -Fc` piped through `gzip`, gzip and archive-TOC check, SHA-256 sidecar, weekly copy, retention, a dated copy of the erasure ledger, `csv_uploads/done` archive only with `BACKUP_ARCHIVE_CSV=true`, optional S3 upload, Prometheus textfile `metrics/educore_backup.prom`. |
| `infra/backup/bin/backup-entrypoint.sh` | Turns the `educore_db_password` Docker secret into a private `PGPASSFILE` (mode 0600 in `/tmp`; `PGPASSWORD` is refused), then `schedule` (default, supercronic) or `backup-now` (one run, used by drills and CI). |
| `infra/backup/bin/backup-health.sh` | Container healthcheck: unhealthy when the last complete backup is older than `BACKUP_MAX_AGE_HOURS` (26). |
| `infra/backup/docker-compose.backup.yml` | Compose override adding the `backup` service and the `educore_backups` volume. |
| `scripts/backup/restore.sh` | Restores a dump into the running database container, then runs `post-restore.sh`; reports success only when both succeeded. Destructive; requires `--yes`. |
| `scripts/backup/post-restore.sh` + `post-restore.sql` | After any restore, before the backend starts: re-inserts the erasure ledger (from the `educore_erasure_ledger` volume or a file), revokes every refresh token and family, increments every session epoch, re-applies the runtime role's privileges and leaves a pending replay for the backend ("Erasure ledger" below). |
| `scripts/backup/tests/restore-drill.sh` | Throwaway-PostgreSQL drill of the above: backup, purge and logout after it, restore of the old dump, checks; and a refused restore without a ledger source. |
| `scripts/backup/verify-latest.sh` | Restores the newest dump into a throwaway container (`--network none`, removed with its volume), checks required tables, at least one successful Flyway migration and a minimum account count, prints the RPO age. |
| `.github/workflows/backup-verify.yml` | Nightly drill: compose stack with throwaway secrets, `backup-now`, `verify-latest.sh`. |

Volume layout (`educore_backups`, mounted at `/backups`):

```
daily/educore-<UTC yyyymmddThhmmssZ>.dump.gz(.sha256)   newest dump of each of the newest 14 UTC days
weekly/educore-<ISO yyyy-Www>.dump.gz(.sha256)          first dump of each ISO week, newest 8 weeks
ledger/erasure-ledger-<UTC timestamp>.log(.sha256)      copy of the erasure ledger, newest of each of the newest 14 UTC days
csv-done/csv-done-<UTC timestamp>.tar.gz(.sha256)       only with BACKUP_ARCHIVE_CSV=true; newest of each of the newest 14 UTC days
last-success, last-upload                               epoch seconds of the last complete run / upload
metrics/educore_backup.prom                             Prometheus textfile (no personal data, mode 0644)
```

Retention counts distinct days and weeks, not files: any number of manual runs on one day replaces only that
day's dump and never evicts older days. Dumps, archives and state files are created with `umask 077` (files
0600, directories 0700, owner uid 70).

## Running it

```bash
# Start the sidecar next to the stack (uses the same .env)
docker compose -f docker-compose.yml -f infra/backup/docker-compose.backup.yml up -d backup

# Take a backup now
docker compose -f docker-compose.yml -f infra/backup/docker-compose.backup.yml run --rm backup backup-now

# Verify the newest backup (works in Git Bash and Linux shells; needs only the Docker CLI)
scripts/backup/verify-latest.sh                      # default source: volume educore_backups
scripts/backup/verify-latest.sh --dir ./offsite-copy # or a host directory with the same layout
scripts/backup/verify-latest.sh --file path/to/educore-20261001T020000Z.dump.gz
```

`verify-latest.sh` checks, in order: SHA-256 sidecar, `gzip -t`, `pg_restore --exit-on-error` (with `pipefail`), the tables in
`--require-tables` (default `account,course,enrollments,flyway_schema_history`), at least one successful row in
`flyway_schema_history`, and `account` holding at least `--min-accounts` rows (default 1). A schema-only or emptied
dump therefore fails. Exit codes: `0` verified and within RPO, `1` usage, restore or content check failed, `3`
verified but older than `--max-age-hours` (default 24).

## Configuration (environment, read from `.env`)

The database connection reuses `EDUCORE_DB_NAME`, `EDUCORE_DB_USERNAME` and `EDUCORE_DB_PASSWORD` (the owner role; the backend's
pool uses the runtime role `EDUCORE_DB_APP_USERNAME`, which has DML only, see `docs/ops/UPGRADE.md`). The password reaches the
sidecar only as the Compose secret `educore_db_password` (file `/run/secrets/educore_db_password`, sourced from
`EDUCORE_DB_PASSWORD`); it is never in the sidecar's environment. Every `BACKUP_*` variable is optional:

| Variable | Default | Meaning |
|---|---|---|
| `BACKUP_SCHEDULE` | `0 2 * * *` | Five-field cron expression evaluated in `BACKUP_TZ`. |
| `BACKUP_TZ` | `UTC` | Time zone of the schedule. File names always use UTC. |
| `BACKUP_RUN_ON_START` | `false` | `true` runs one backup when the container starts. |
| `BACKUP_KEEP_DAILY` / `BACKUP_KEEP_WEEKLY` / `BACKUP_KEEP_CSV` | `14` / `8` / `14` | Local retention in distinct days / ISO weeks / days (the ledger copies follow `BACKUP_KEEP_DAILY`). |
| `BACKUP_ARCHIVE_CSV` | `false` | `true` also archives `csv_uploads/done`. Off because processed CSV files hold names and student numbers: an archive would keep an erased student for `BACKUP_KEEP_CSV` more days, outside the reach of the purge (AC-08). The backend no longer keeps processed files by default (`educore.ingestion.retain-processed-days = 0`), so there is normally nothing to archive; existing archives still age out. |
| `BACKUP_MAX_AGE_HOURS` | `26` | Healthcheck limit for the age of the last complete backup. |
| `BACKUP_S3_BUCKET` | empty | Enables the off-site upload when set. |
| `BACKUP_S3_ENDPOINT` | empty | S3 API endpoint, e.g. `https://s3.eu-central-1.amazonaws.com` or an R2/MinIO/Wasabi URL. Required with a bucket. |
| `BACKUP_S3_REGION` | empty | Region, when the provider needs one. |
| `BACKUP_S3_PREFIX` | `educore` | Key prefix inside the bucket. |
| `BACKUP_S3_PROVIDER` | `Other` | rclone S3 provider name (`AWS`, `Cloudflare`, `Minio`, `Wasabi`, ...). |
| `BACKUP_S3_ACCESS_KEY_ID` / `BACKUP_S3_SECRET_ACCESS_KEY` | empty | Credentials of a key that may only write to that bucket/prefix. Required with a bucket. |

Off-site upload uses `rclone copy` (never `sync`), so the local pruning is not mirrored to the bucket. That is **not**
protection of remote history: anyone holding the access key (including an attacker on the host) can delete or
overwrite objects. Protection must come from the bucket, and these settings are required before relying on the copy:

- versioning enabled, plus Object Lock (or the provider's equivalent retention lock) in governance/compliance mode for at least 30 days;
- an access key that may only `PutObject`/`ListBucket` on the `BACKUP_S3_PREFIX` prefix (no delete, no lifecycle or bucket-policy changes);
- remote retention as a bucket lifecycle rule (for example expire noncurrent and current versions after 120 days);
- private bucket with encryption at rest: backups contain personal data.

On a Linux host the sidecar writes the named volume as uid 70; no host directory permissions are needed. `csv_uploads/done` and the
erasure ledger volume are mounted read-only.

## Erasure ledger: restores never bring erased people back

A dump is a snapshot of the past: restoring one brings back every account purged after it was taken (with its old password
hash) and every refresh token revoked after it (logouts, password changes, deletion requests, deactivations). The purge cannot
reach the dumps, so the restore has to re-apply the erasures (AC-09):

1. Every purge writes one line to the **erasure ledger** in the purging transaction: the `erasure_ledger` table and the
   append-only file `/var/lib/educore/erasure-ledger.log` on the volume `educore_erasure_ledger` (`EDUCORE_ERASURE_LEDGER_FILE`,
   required in prod). The line is forced to disk before the purge commits; a failed write fails the purge. Format:
   `v1 <purgedAt UTC> <account digest> <username digest> <student number digest or ->`, each digest an HMAC-SHA-256 under a
   key derived from `EDUCORE_LOGIN_PEPPER` (label `educore/erasure-ledger/v1`): no personal data, and no way to test a guess
   without the pepper. The volume is never part of a dump, so a restore cannot rewind it. The backup sidecar keeps a dated copy
   (`ledger/`) next to the dumps for the case where the host and the volume are lost.
2. `restore.sh` refuses to start when it has no ledger source (the volume, `--ledger-file` with a `ledger/` copy, or
   `--no-ledger` when nothing was ever purged), before anything is dropped. After `pg_restore` it runs `post-restore.sh`, which
   in one transaction re-inserts the ledger lines, revokes **every** refresh token and family, increments **every** account's
   session epoch (all access tokens issued before stop working) and records a pending `restore_replay`. Only then does the
   restore report success; on a failure it says "do not start the backend". `--skip-replay` skips the step explicitly and
   prints a warning.
3. At startup, before it accepts a request, the backend (`ErasureLedgerReplay`) completes the pending replay: every restored
   account whose id digest is in the ledger is purged again (`ACCOUNT_PURGED`, trigger `LEDGER_REPLAY`). A restored account
   that matches a ledger entry only by username or student number digest is **not** purged (the same student number may have
   been registered again after the purge) but logged by pseudonym for review. If somebody restored without `post-restore.sh`,
   the backend notices that the ledger file holds entries the database does not know and does the same on its own (source
   `LEDGER_FILE_AHEAD`, including the session reset).

Everybody signs in again after a restore; that is intended (nobody can tell which sessions the dump revived). PENDING webhook
deliveries contained in the dump are sent again (at-least-once; receivers deduplicate on `X-EduCore-Delivery`).

**Maximum erasure-propagation window.** A purged person stays inside the backups taken before the purge until they rotate out:
locally at most 14 days in `daily/` and at most 8 ISO weeks (about 56 days) in `weekly/`, so **at most 56 days** after the purge;
off-site as long as the bucket's lifecycle rule keeps objects (120 days in the example above; keep that rule at the shortest
period the Object Lock allows). State the longer of the two as the erasure-propagation period in the privacy notice. Within that
window the ledger guarantees that a restored dump never serves the purged account or a revoked session again. Purges made
before the ledger existed are not in it: until the dumps older than that upgrade rotate out, re-apply them as listed in
`docs/ops/DATA_RETENTION.md` ("Backups"). Rotating `EDUCORE_LOGIN_PEPPER` makes the existing ledger entries unmatchable: keep
the old pepper until every dump older than the rotation has aged out.

## Monitoring

- Docker healthcheck (`backup-health.sh`, image and compose): the container turns `unhealthy` when the last complete
  backup is older than 26 h; before the first run the container start time is the reference.
- Prometheus: every run, failed or not, writes `metrics/educore_backup.prom` (`educore_backup_last_run_success`,
  `educore_backup_last_success_timestamp_seconds`, `educore_backup_last_dump_size_bytes`,
  `educore_backup_offsite_enabled`, `educore_backup_last_upload_success_timestamp_seconds`). Serve it with
  node-exporter's textfile collector; rules `EduCoreBackupStale`, `EduCoreBackupFailed`,
  `EduCoreBackupMetricsMissing` and `EduCoreBackupUploadStale` are in `infra/monitoring/alerts.example.yml`.

## Restore procedure (incident)

1. Declare the incident and note the time (RTO clock starts).
2. Stop writers: `docker compose stop educore-backend`.
3. Pick the dump: newest `daily/` for data loss, an older `daily/` or `weekly/` for corruption that predates it. Copy it out of the volume, e.g.
   `docker run --rm -v educore_backups:/b:ro -v "$PWD:/out" alpine cp /b/daily/<file> /out/`
4. Check it: `scripts/backup/verify-latest.sh --file <file>`.
5. Restore: `scripts/backup/restore.sh --yes <file>` (default container `educore-postgres`; `--container`/`--database` to override; the erasure ledger is read from the volume `educore_erasure_ledger`, or pass `--ledger-file <newest ledger/ copy>` when the volume was lost). The dump is staged in a private `mktemp` file inside the container (gzip-tested, removed on exit) and restored in one transaction: on failure the database is unchanged. The post-restore step follows automatically; the output must end with `restore: complete`.
6. Start the backend: `docker compose start educore-backend`; Flyway validates the schema, then the backend replays the erasure ledger before it serves (log line `Restore detected: erasure ledger replayed ...`; review any `accountsToReview`). Check `/actuator/health` and log in as an administrator (every user signs in again).
7. CSV history is not restored: processed files are not kept (`educore.ingestion.retain-processed-days`), and `csv-done/` archives exist only with `BACKUP_ARCHIVE_CSV=true`. Never extract such an archive back into `csv_uploads/` after a purge: it would bring the purged students' rows back to disk.
8. Record the achieved RPO (dump age) and RTO (elapsed time) in the incident notes.

## Quarterly drill checklist

Run in the first week of each quarter; file the results with the date and the operator.

- [ ] The nightly `backup-verify` workflow has been green for the whole quarter (or every failure has a linked fix).
- [ ] `docker compose ... ps backup` reports `healthy`; `docker compose ... logs backup` shows a successful run in the last 24 h.
- [ ] `daily/` holds at most one dump per day for 14 days, `weekly/` at most 8; no `.partial.*` files remain; files are mode 0600.
- [ ] `scripts/backup/verify-latest.sh` returns `RESULT OK` against the production volume; the account count matches the live database within the expected daily change.
- [ ] A weekly dump older than 30 days restores with `verify-latest.sh --file ... --max-age-hours 2000`.
- [ ] If off-site upload is enabled: download the newest object from the bucket on a different machine and verify it with `--dir`.
- [ ] Full restore rehearsal on a staging copy with `restore.sh`, backend started against it, administrator login works; measured RTO is below 1 h.
- [ ] `scripts/backup/tests/restore-drill.sh` ends with `RESULT OK` (backup, purge and logout after it, restore of the old dump, ledger replay, every session revoked, runtime role intact, restore refused without a ledger source).
- [ ] The newest `ledger/` copy has as many lines as `/var/lib/educore/erasure-ledger.log` on the backend (`docker compose exec educore-backend wc -l /var/lib/educore/erasure-ledger.log`).
- [ ] Bucket versioning and Object Lock are still enabled; the S3 key can write only its prefix and cannot delete; rotate it if it is older than one year.
- [ ] This document still matches the scripts (paths, defaults, retention).
