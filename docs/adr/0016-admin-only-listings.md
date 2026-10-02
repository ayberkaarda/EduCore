# 0016. Student and account listings are ADMIN-only; responses expose no internal flags

- Status: Accepted (sorting amended by [ADR 0020](0020-paging-sorting-and-input-validation.md); the meaning of
  `deleted` refined by [ADR 0029](0029-account-lifecycle-and-grace-period.md))
- Date: 2026-10-02
- Original decision: D-NEW-09

## Context

Ordinary users could list other students and accounts, and listings returned an internal `deleted` flag and
accepted an arbitrary `sortBy` property that reached the persistence layer.

## Decision

- Listing students and accounts is ADMIN-only. A USER sees only their own profile and enrollments
  (`/api/v1/me/**`).
- Admin listings take `deleted=true|false` (default `false`) instead of returning a `deleted` field in
  `AccountResponse`.
- The free `sortBy` parameter is removed; the default order is first name, then id, with `direction` kept.

## Consequences

Positive:

- Users read only their own data, as the RBAC baseline requires.
- No internal flag leaks through responses; no raw sort property reaches `Sort.by`.

Negative:

- Clients that sorted by arbitrary properties lost that ability until a whitelisted `sort` parameter was
  introduced ([ADR 0020](0020-paging-sorting-and-input-validation.md)).

## References

- [`src/main/java/com/educore/account/AccountAdminController.java`](../../src/main/java/com/educore/account/AccountAdminController.java)
- [`src/main/java/com/educore/account/AccountResponse.java`](../../src/main/java/com/educore/account/AccountResponse.java)
- [`src/test/java/com/educore/authz/AuthorizationMatrixIT.java`](../../src/test/java/com/educore/authz/AuthorizationMatrixIT.java)
- [`docs/security/RBAC_MATRIX.md`](../security/RBAC_MATRIX.md)
