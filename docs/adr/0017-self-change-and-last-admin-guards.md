# 0017. Self-change and last-ADMIN guards answer 409 and serialise on ADMIN rows

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-10

## Context

An ADMIN could change their own role, delete their own account, or remove the last active ADMIN, leaving the
system without an administrator. Two ADMINs demoting or deleting each other at the same moment could both
succeed if the check and the change were not serialised.

## Decision

- Self role change, self delete and removing the last active ADMIN answer `409` problems with the codes
  `account/self-role-change`, `account/self-delete` and `account/last-admin`. `403` stays reserved for the RBAC
  matrix: the caller may use the route, but the current state forbids the change.
- Before checking, the guard locks every active ADMIN row with `SELECT ... FOR UPDATE`
  (`AccountRepository.lockActiveAdminIds()`). The same lock order is used by the account lifecycle operations.

## Consequences

Positive:

- The system always keeps at least one active ADMIN, also under concurrency (`LastAdminGuardIT`).
- Clients can tell an authorization failure (403) from a state conflict (409).

Negative:

- Role and delete operations on ADMIN accounts serialise on all ADMIN rows; acceptable for the small number of
  administrators.

## References

- [`src/main/java/com/educore/account/AccountAdminService.java`](../../src/main/java/com/educore/account/AccountAdminService.java)
- [`src/main/java/com/educore/repository/AccountRepository.java`](../../src/main/java/com/educore/repository/AccountRepository.java)
- [`src/main/java/com/educore/lifecycle/AccountLifecycleService.java`](../../src/main/java/com/educore/lifecycle/AccountLifecycleService.java)
- [`src/test/java/com/educore/authz/LastAdminGuardIT.java`](../../src/test/java/com/educore/authz/LastAdminGuardIT.java)
