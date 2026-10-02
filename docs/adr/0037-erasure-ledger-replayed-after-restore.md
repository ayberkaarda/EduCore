# 0037. An erasure ledger replayed after every restore

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-91

## Context

Daily dumps are kept for up to 56 days locally (14 daily, 8 weekly) and longer off-site. Restoring one replaced
every table. Accounts purged after the dump came back with their password hashes, and refresh tokens revoked after
the dump became valid again, so a captured cookie or a logged-out browser could sign in once more. The documented
remedy was to re-run purges by hand (threat model R-06, attack chain AC-09).

## Decision

- Every purge writes keyed digests of the account id, the username and the student number to the
  `erasure_ledger` table (`V33`). The key is derived from the pepper with the label `educore/erasure-ledger/v1`.
  The same digests are written as one line to an append-only file on the `educore_erasure_ledger` volume, which
  is outside the dump. The line is forced to disk before the purge commits, and a failed ledger write fails the
  purge.
- `scripts/backup/restore.sh` refuses to start without a ledger source and runs `post-restore.sh` after
  `pg_restore`. That script re-inserts the ledger, revokes every refresh token and family, increments every
  session epoch, re-grants the runtime role and records a pending `restore_replay`. The restore reports success
  only if both steps passed; `--skip-replay` skips the step explicitly and warns.
- At startup, before it accepts a request, the backend (`ErasureLedgerReplay`) purges again every restored account
  whose id digest is in the ledger. It also notices a ledger file that is ahead of the database (a restore without
  the script) and then does the same, including the session reset.
- Accounts that match only by username or student number digest are logged for review, not purged, because a
  student may have been registered again after the purge.

## Consequences

Positive:

- A restore never brings an erased person or an ended session back.
- The scripts need no secret: matching needs the pepper, which only the backend holds, so the purge logic is not
  duplicated in SQL.

Negative:

- Login attempts and automatic deny rules revert to the state at dump time, and PENDING webhook deliveries may be
  sent again (receivers de-duplicate by `X-EduCore-Delivery`).
- The ledger grows forever until pruning exists (BACKLOG B-090). The review of username-only matches is read
  from the log (BACKLOG B-094).

## References

- [`src/main/java/com/educore/lifecycle/ErasureLedger.java`](../../src/main/java/com/educore/lifecycle/ErasureLedger.java)
- [`src/main/java/com/educore/lifecycle/ErasureLedgerReplay.java`](../../src/main/java/com/educore/lifecycle/ErasureLedgerReplay.java)
- [`src/main/resources/db/migration/V33__erasure_ledger.sql`](../../src/main/resources/db/migration/V33__erasure_ledger.sql)
- [`scripts/backup/restore.sh`](../../scripts/backup/restore.sh), [`scripts/backup/post-restore.sh`](../../scripts/backup/post-restore.sh), [`scripts/backup/post-restore.sql`](../../scripts/backup/post-restore.sql)
- [`src/test/java/com/educore/lifecycle/ErasureLedgerRestoreIT.java`](../../src/test/java/com/educore/lifecycle/ErasureLedgerRestoreIT.java)
- [`src/test/java/com/educore/lifecycle/ErasureLedgerTest.java`](../../src/test/java/com/educore/lifecycle/ErasureLedgerTest.java)
- [`docs/ops/BACKUP_RESTORE.md`](../ops/BACKUP_RESTORE.md)
- Related: [0029](0029-account-lifecycle-and-grace-period.md), [0030](0030-audit-pseudonymisation-and-retention.md)
