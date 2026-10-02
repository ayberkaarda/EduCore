# 0034. Server-enforced password-change scope for temporary and bootstrap passwords

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-82

## Context

Students created by an ADMIN or by a CSV import, and the bootstrap ADMIN, receive passwords that pass through
people, `.env` files and shell history. They are flagged `mustChangePassword`. Before this decision only a
browser route guard enforced the flag, so the same password worked as a full credential for every API call made
outside the web UI (threat model R-03, attack chain AC-04).

## Decision

- A session of an account flagged `mustChangePassword` gets the single authority
  `ACCOUNT_PASSWORD_CHANGE_REQUIRED` and no role.
- `PasswordChangeRequiredScopeFilter` runs in both security filter chains. It allows exactly
  `GET /api/v1/auth/me`, `POST /api/v1/auth/password`, `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout`;
  every other request answers 403 `account/password-change-required`.
- `GET /api/v1/me` is deliberately outside the scope: `GET /api/v1/auth/me` already gives the UI what it needs.
- The frontend routes such a session straight to the change-password screen.

## Consequences

Positive:

- A temporary or bootstrap password can only be used to set a new password.
- A role-less authority plus an exact allow-list fails closed for every route, including routes added later.
  This is the same pattern as the restore-only scope of [0029](0029-account-lifecycle-and-grace-period.md).

Negative:

- The bootstrap password remains readable in the container environment until the container is recreated. This is
  an operator boundary; after the first change the value is no longer a credential.

## References

- [`src/main/java/com/educore/security/PasswordChangeRequiredScopeFilter.java`](../../src/main/java/com/educore/security/PasswordChangeRequiredScopeFilter.java)
- [`src/main/java/com/educore/security/JwtAuthenticationFilter.java`](../../src/main/java/com/educore/security/JwtAuthenticationFilter.java)
- [`src/main/java/com/educore/security/SecurityConfig.java`](../../src/main/java/com/educore/security/SecurityConfig.java)
- [`src/test/java/com/educore/authz/PasswordChangeRequiredScopeIT.java`](../../src/test/java/com/educore/authz/PasswordChangeRequiredScopeIT.java)
- [`src/test/java/com/educore/config/AdminBootstrapIT.java`](../../src/test/java/com/educore/config/AdminBootstrapIT.java)
- [`docs/security/RBAC_MATRIX.md`](../security/RBAC_MATRIX.md)
