# 0015. One URL prefix per role: `/api/v1/admin/**`, `/api/v1/me/**`, read-only catalog

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-07

## Context

The previous routes mixed roles under shared paths and accepted `accountId` in bodies and paths of calls made
by ordinary users, which invited insecure direct object references. Authorization had to be decided per
method, not per route.

## Decision

- ADMIN capabilities live under `/api/v1/admin/**` (accounts, courses, imports, IP allocations, IP rules, job
  logs, security events, webhooks). `SecurityConfig` requires `ROLE_ADMIN` for the whole prefix, and method
  security (`@PreAuthorize`) repeats the check.
- The caller's own data lives under `/api/v1/me/**`, always derived from the authenticated principal, never
  from an id in the request.
- The read-only course catalog for signed-in users is `GET /api/v1/courses`; the anonymous public API is
  `/api/v1/public/**` ([ADR 0028](0028-public-course-catalog.md)).
- The previous routes are removed without compatibility aliases. `docs/api/ROUTES.md` maps every old route to
  its replacement, and the frontend was adapted from it.

## Consequences

Positive:

- The RBAC matrix (`docs/security/RBAC_MATRIX.md`) is enforced at the URL layer before any controller runs,
  and verified by `AuthorizationMatrixIT`.
- IDOR-prone request shapes are no longer reachable.

Negative:

- Breaking change for any client of the old routes.

## References

- [`src/main/java/com/educore/security/SecurityConfig.java`](../../src/main/java/com/educore/security/SecurityConfig.java)
- [`src/main/java/com/educore/account/MeController.java`](../../src/main/java/com/educore/account/MeController.java)
- [`src/main/java/com/educore/account/AccountAdminController.java`](../../src/main/java/com/educore/account/AccountAdminController.java)
- [`src/main/java/com/educore/course/CourseController.java`](../../src/main/java/com/educore/course/CourseController.java)
- [`src/test/java/com/educore/authz/AuthorizationMatrixIT.java`](../../src/test/java/com/educore/authz/AuthorizationMatrixIT.java)
- [`docs/api/ROUTES.md`](../api/ROUTES.md)
- [`docs/security/RBAC_MATRIX.md`](../security/RBAC_MATRIX.md)
