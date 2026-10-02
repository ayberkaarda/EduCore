# JWT signing key rotation

EduCore signs access tokens with HS256. Two environment variables hold the keys:

| Variable | Role |
|---|---|
| `EDUCORE_JWT_SECRET` | Current key. Every new access token is signed with it. Required. |
| `EDUCORE_JWT_SECRET_PREVIOUS` | Previous key. Accepted for verification only, so tokens issued before a rotation stay valid until they expire. Optional. |

Both values are base64 and must decode to at least 32 bytes; startup fails with a message naming the variable otherwise. Generate a value with `openssl rand -base64 48`.

## How a token names its key

Each token carries a `kid` header: the first 8 bytes (hex) of SHA-256 over a fixed label and the key bytes. The verifier looks the `kid` up among the current and previous keys. A token whose `kid` is missing or matches neither key is rejected, as is any algorithm other than HS256. Because the `kid` is derived from the key itself, a token keeps verifying when its key moves from `EDUCORE_JWT_SECRET` to `EDUCORE_JWT_SECRET_PREVIOUS`.

Access tokens live 15 minutes (`educore.security.jwt.access-token-ttl`); verification allows 30 seconds of clock skew.

## Routine rotation (no forced sign-out)

Every instance verifies with both variables but signs only with `EDUCORE_JWT_SECRET`. During a rolling
deployment old and new instances serve traffic side by side, so a token signed by any instance must verify
on every other instance at every moment. Rotate in three stages and finish each stage on **every** instance
before starting the next:

1. **Stage 1, introduce the new key as verify-only.** Generate it (`openssl rand -base64 48`). Keep
   `EDUCORE_JWT_SECRET` unchanged and set `EDUCORE_JWT_SECRET_PREVIOUS` to the **new** key. Roll this out to
   all instances. Nothing is signed with the new key yet, but every instance now accepts it.
2. **Stage 2, switch signing.** Set `EDUCORE_JWT_SECRET` to the new key and `EDUCORE_JWT_SECRET_PREVIOUS` to
   the old key. Roll this out. Instances already switched sign with the new key, which the instances not
   yet switched accept since stage 1; tokens signed with the old key keep verifying everywhere.
3. **Stage 3, retire the old key.** Once stage 2 runs on every instance, wait at least one access token
   lifetime plus skew (15 minutes 30 seconds; wait 20 minutes to be safe), then remove
   `EDUCORE_JWT_SECRET_PREVIOUS` and roll out again. Tokens signed with the old key are now rejected.

Skipping stage 1 (switching signing directly) makes a token issued by an already switched instance fail
with 401 on an instance still running the old configuration. With a single instance, stages 1 and 2 can be
combined into one restart.

Refresh tokens are opaque random values stored as SHA-256 hashes in `refresh_token`; they do not depend on the JWT key, so sessions continue across a rotation: the next refresh issues an access token signed with the new key.

## Residual access-token validity after logout and password change

Access tokens are stateless and are not revoked individually. Logout revokes the refresh token family and a
password change revokes every refresh token of the account, so no new access token can be obtained, but an
access token issued before the logout or password change stays usable until it expires: up to **15 minutes after
issue plus 30 seconds of clock skew**. Soft-deleting or removing an account, or removing its role, takes
effect on the next request because the account is loaded for every bearer token.

Session epoch (since fix1, `V22__account_session_epoch.sql`): every access token carries the account's
`session_epoch` (`sep` claim; tokens without it count as epoch 0) and `JwtAuthenticationFilter` rejects a token
whose epoch differs from the stored one. The owner's deletion request, an ADMIN soft delete, an ADMIN restore and
the owner's restore increment the epoch and revoke every refresh family, so for these lifecycle changes there is
**no residual validity**: earlier access tokens answer 401 on their next request and no session survives a
deactivate → restore cycle (R-16, R-20). Logout and password change keep the residual window above (BACKLOG
B-084). When a stolen access
token must stop working at once, use the emergency rotation below: it invalidates every access token at once, and clients
with a valid refresh token obtain a new one.
`AccessTokenResidualValidityIT` pins this behaviour.

## Emergency rotation (key compromised)

1. Generate a new key and set it as `EDUCORE_JWT_SECRET`.
2. Leave `EDUCORE_JWT_SECRET_PREVIOUS` unset (or empty): every token signed with the compromised key is rejected immediately.
3. Restart or redeploy every backend instance.
4. If refresh tokens may also be compromised (for example a database leak), revoke them all so every user signs in again (families first: that
   row lock makes rotations in flight finish before the token update):

   ```sql
   BEGIN;
   UPDATE refresh_token_family SET revoked_at = now() WHERE revoked_at IS NULL;
   UPDATE refresh_token SET revoked_at = now() WHERE revoked_at IS NULL;
   COMMIT;
   ```

5. Review `security_event` for unusual `AUTH_LOGIN_SUCCESS` and `AUTH_REFRESH_REUSE` entries during the exposure window.

## Related secret: `EDUCORE_LOGIN_PEPPER`

`EDUCORE_LOGIN_PEPPER` keys the HMAC-SHA-256 under which usernames are stored in `login_attempt`. It is not used for tokens. The audit pseudonyms of purged accounts use a key derived from it for that purpose only (`HMAC(label, pepper)`, `lifecycle/Pseudonyms`), so a login username can never reproduce a pseudonym; rotating the pepper also changes the pseudonym of later purges (earlier ones stay as written). Rotating it only resets the failed-login counters (existing rows no longer match), which briefly lifts active lockouts; rotate it when it may have leaked, not on a schedule.

## Where the values live

- Local development and Docker Compose: the git-ignored `.env` file (see `.env.example`).
- Production: the deployment platform's secret store, injected as environment variables. Never commit a key, never log it, and refer to it only by variable name in tickets and reports.
