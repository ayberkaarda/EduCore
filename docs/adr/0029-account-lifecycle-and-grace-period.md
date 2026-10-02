# 0029. Account lifecycle states, a 30-day deletion grace period and a nightly purge

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-70

## Context

Accounts were only soft-deleted (`account.deleted`), so personal data was never really erased. Users need a
way to request erasure and to change their mind, and there is no e-mail or other secondary channel for a
restore link.

## Decision

- `V21__account_lifecycle.sql` replaces `account.deleted` with `status` (`ACTIVE`, `DEACTIVATED`,
  `PENDING_DELETION`, `DELETED`), `deleted_at` and `delete_after`; `deleted = 0` became `ACTIVE`, any other value
  `DEACTIVATED`. A check constraint ties the dates to the status.
- `DELETE /api/v1/me` with the current password moves the account to `PENDING_DELETION` for
  `educore.lifecycle.grace-days` (30) and revokes every refresh token family.
- During the grace period the owner can still sign in, but with the restore-only scope: `GET /api/v1/me`,
  `POST /api/v1/me/restore` and `POST /api/v1/auth/logout`; everything else is 403 `account/pending-deletion`,
  enforced by `PendingDeletionScopeFilter` and an authority without any role.
- `AccountPurgeJob` (nightly, `FOR UPDATE SKIP LOCKED`, one account per transaction) hard-deletes accounts whose
  grace period ended. An ADMIN can purge at once with `mode=hard&confirm=<username>`. `DELETED` is written only
  inside the purge transaction.
- Admin listings map `deleted=false` to `ACTIVE` and `deleted=true` to any other status.

## Consequences

Positive:

- Real erasure with a self-service undo that needs no new secret channel.
- The role-less authority plus an exact path allow-list fails closed for every route, including future ones.
- Concurrent purge runs (threads or instances) are disjoint without a scheduler lock.

Negative:

- Data stays for up to 30 days after the request.
- `V21` sorts below already released migrations; databases at V42 need a one-time
  `SPRING_FLYWAY_OUT_OF_ORDER=true` start (`docs/ops/UPGRADE.md`).

## References

- [`src/main/resources/db/migration/V21__account_lifecycle.sql`](../../src/main/resources/db/migration/V21__account_lifecycle.sql)
- [`src/main/java/com/educore/entity/AccountStatus.java`](../../src/main/java/com/educore/entity/AccountStatus.java)
- [`src/main/java/com/educore/lifecycle/AccountLifecycleService.java`](../../src/main/java/com/educore/lifecycle/AccountLifecycleService.java)
- [`src/main/java/com/educore/lifecycle/AccountPurgeJob.java`](../../src/main/java/com/educore/lifecycle/AccountPurgeJob.java)
- [`src/main/java/com/educore/security/PendingDeletionScopeFilter.java`](../../src/main/java/com/educore/security/PendingDeletionScopeFilter.java)
- [`src/main/java/com/educore/security/ActiveAccount.java`](../../src/main/java/com/educore/security/ActiveAccount.java)
- [`src/test/java/com/educore/lifecycle/AccountLifecycleIT.java`](../../src/test/java/com/educore/lifecycle/AccountLifecycleIT.java)
- [`docs/ops/DATA_RETENTION.md`](../ops/DATA_RETENTION.md)
- [`docs/ops/UPGRADE.md`](../ops/UPGRADE.md)
