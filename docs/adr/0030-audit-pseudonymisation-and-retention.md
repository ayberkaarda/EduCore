# 0030. Pseudonymise purged accounts in the audit trail and bound retention

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-71

## Context

Purging an account ([ADR 0029](0029-account-lifecycle-and-grace-period.md)) must remove what identifies its
owner, but deleting its audit rows would also erase evidence of actions on other accounts. Login attempts and
security events must not be kept forever.

## Decision

- `security_event` gains `actor_pseudonym` and `target_pseudonym`; check constraints allow an id or a pseudonym,
  never both, and require the format `purged:<16 hex>`.
- The purge (`AccountPurger`) replaces the account id with `purged:<first 16 hex of HMAC-SHA-256(EDUCORE_LOGIN_PEPPER,
  "account:" + id)>` (`Pseudonyms`), removes the client IP of events the account caused, removes the
  `usernameHash` detail of failed logins against it, and deletes its `login_attempt` rows through the username
  HMAC. A keyed HMAC with the existing pepper gives the intended property without a second secret.
- No tombstone of purged identifiers is kept, so re-registration is allowed (`docs/ops/DATA_RETENTION.md`
  describes the alternative).
- A purge queues `account.deleted` with `{accountId, mode: HARD}` after commit through
  `AccountDeletedWebhookRelay`.
- `RetentionJob` (nightly) deletes `login_attempt` rows older than 90 days and `security_event` rows older than
  365 days.

## Consequences

Positive:

- Events stay countable and correlatable without pointing at a person; existing indexes, types and the admin
  API stay unchanged.

Negative:

- Rotating `EDUCORE_LOGIN_PEPPER` changes future pseudonyms, so events purged before and after a rotation no
  longer correlate.
- Audit evidence older than 365 days is gone.

## References

- [`src/main/resources/db/migration/V21__account_lifecycle.sql`](../../src/main/resources/db/migration/V21__account_lifecycle.sql)
- [`src/main/java/com/educore/lifecycle/AccountPurger.java`](../../src/main/java/com/educore/lifecycle/AccountPurger.java)
- [`src/main/java/com/educore/lifecycle/Pseudonyms.java`](../../src/main/java/com/educore/lifecycle/Pseudonyms.java)
- [`src/main/java/com/educore/lifecycle/RetentionJob.java`](../../src/main/java/com/educore/lifecycle/RetentionJob.java)
- [`src/main/java/com/educore/lifecycle/AccountDeletedWebhookRelay.java`](../../src/main/java/com/educore/lifecycle/AccountDeletedWebhookRelay.java)
- [`src/test/java/com/educore/lifecycle/RetentionJobIT.java`](../../src/test/java/com/educore/lifecycle/RetentionJobIT.java)
- [`docs/ops/DATA_RETENTION.md`](../ops/DATA_RETENTION.md)
