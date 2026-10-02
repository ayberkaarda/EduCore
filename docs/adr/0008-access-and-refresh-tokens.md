# 0008. Short-lived HS256 access tokens and rotating opaque refresh tokens

- Status: Accepted
- Date: 2026-09-25
- Original decision: D-08

## Context

The single-page application needs stateless API authentication and a way to stay signed in without keeping a
long-lived bearer token in browser storage. A stolen refresh token must be detectable.

## Decision

- Access token: JWT signed with HS256 only (`JwtService`; any other algorithm is rejected), `sub` = account id,
  issuer `educore`, audience `educore-api`, lifetime `educore.security.jwt.access-token-ttl` (15 minutes). The
  secret comes from `EDUCORE_JWT_SECRET` (base64, at least 32 decoded bytes); `EDUCORE_JWT_SECRET_PREVIOUS` is
  accepted for verification only during a key rotation. The token is returned in the response body and kept in
  memory by the SPA.
- Refresh token: 256 random bits, base64url encoded, sent as an `HttpOnly`, `SameSite=Strict` cookie scoped to
  `/api/v1/auth` (`Secure` in every profile except `dev`), lifetime 14 days. Only its SHA-256 hex digest is
  stored (`refresh_token.token_hash`). Each use rotates it within its family; presenting a revoked token
  revokes the whole family. The `refresh_token_family` row is the family's lock for rotation, reuse detection,
  logout and password change (`V11__refresh_token_family.sql`).
- `JwtAuthenticationFilter` loads the account on every request, so role and status changes apply immediately;
  the token's role claim is not trusted.

## Consequences

Positive:

- A leaked database does not reveal usable refresh tokens; refresh token reuse is detected and contained.
- No long-lived credential is readable by page scripts.

Negative:

- One account lookup per authenticated request.
- An access token stays valid until it expires (up to 15 minutes) unless the account itself changes status.

## References

- [`src/main/java/com/educore/security/JwtService.java`](../../src/main/java/com/educore/security/JwtService.java)
- [`src/main/java/com/educore/security/JwtAuthenticationFilter.java`](../../src/main/java/com/educore/security/JwtAuthenticationFilter.java)
- [`src/main/java/com/educore/auth/RefreshTokenService.java`](../../src/main/java/com/educore/auth/RefreshTokenService.java)
- [`src/main/resources/db/migration/V10__auth_tokens.sql`](../../src/main/resources/db/migration/V10__auth_tokens.sql)
- [`src/main/resources/db/migration/V11__refresh_token_family.sql`](../../src/main/resources/db/migration/V11__refresh_token_family.sql)
- [`src/test/java/com/educore/auth/RefreshReuseDetectionIT.java`](../../src/test/java/com/educore/auth/RefreshReuseDetectionIT.java)
- [`docs/security/KEY_ROTATION.md`](../security/KEY_ROTATION.md)
