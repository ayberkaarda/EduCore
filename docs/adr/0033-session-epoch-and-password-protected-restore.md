# 0033. Session epoch as a session boundary, and a password-protected restore

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-81

## Context

Access tokens are stateless and live for 15 minutes ([0008](0008-access-and-refresh-tokens.md)). Lifecycle
changes did not end them, and an ADMIN soft delete did not revoke refresh families. A cookie held across
deactivation and restore therefore kept working. A token copied before a deletion request could also call
`POST /api/v1/me/restore` without the password and silently undo the owner's erasure request (threat model R-16
and R-20, attack chain AC-10).

## Decision

- `account.session_epoch` (`V22`) is copied into every access token as the `sep` claim. `JwtAuthenticationFilter`
  compares it with the database value on every request; a token with an older epoch is treated as anonymous.
- The owner's deletion request, an ADMIN soft delete, an ADMIN restore and the owner's restore each increment the
  epoch and revoke every refresh family of the account.
- `POST /api/v1/me/restore` requires `{currentPassword}`. A wrong password counts towards the login lockout. The
  answer is a new session in the same shape as a login (body plus refresh cookie).
- Logout and password change keep the documented 15-minute residual of access tokens.

## Consequences

Positive:

- Lifecycle changes end every session immediately, without a token deny list and without a database write per
  request; the account row is already read on every request.
- Answering the restore with a session avoids a second sign-in right after the password was typed.

Negative:

- Access tokens issued before a logout or a password change stay valid for at most 15 minutes. Incrementing the
  epoch on password change would end them at once; logout would need a per-family epoch (BACKLOG B-084).

## References

- [`src/main/java/com/educore/security/JwtAuthenticationFilter.java`](../../src/main/java/com/educore/security/JwtAuthenticationFilter.java)
- [`src/main/java/com/educore/lifecycle/AccountLifecycleService.java`](../../src/main/java/com/educore/lifecycle/AccountLifecycleService.java)
- [`src/main/java/com/educore/account/MeController.java`](../../src/main/java/com/educore/account/MeController.java)
- [`src/main/resources/db/migration/V22__account_session_epoch.sql`](../../src/main/resources/db/migration/V22__account_session_epoch.sql)
- [`src/test/java/com/educore/lifecycle/AccountLifecycleIT.java`](../../src/test/java/com/educore/lifecycle/AccountLifecycleIT.java)
- [`src/test/java/com/educore/auth/AccessTokenResidualValidityIT.java`](../../src/test/java/com/educore/auth/AccessTokenResidualValidityIT.java)
- Related: [0008](0008-access-and-refresh-tokens.md), [0029](0029-account-lifecycle-and-grace-period.md)
