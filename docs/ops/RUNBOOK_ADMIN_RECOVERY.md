# Runbook: administrator recovery (break-glass)

Use this when no administrator can sign in: every ADMIN is locked out, denied by an IP rule, deactivated, or its
password is lost. Everything here is done by an operator with shell access to the Docker host and the database;
there is deliberately no code backdoor (no recovery endpoint, no master password). Every step is a plain SQL
statement against the `educore` database and leaves the normal audit trail intact.

Normal path first: an ADMIN who can still sign in uses `POST /api/v1/admin/accounts/{accountId}/unlock-login`
(clears the account's failed logins, audited `ACCOUNT_LOGIN_UNLOCKED`), the deny-rule screens
(`/api/v1/admin/ip-rules`) and `POST /api/v1/admin/accounts/{accountId}/restore`. Only when nobody can, continue
below.

## 0. Before you start

1. Take a backup (`docs/ops/BACKUP_RESTORE.md`) and note the time: every change below is visible in the database,
   record it in your incident notes as well.
2. Open a database shell on the host (compose service `postgres-db`; the variables are those of `.env`):

   ```sh
   docker compose exec postgres-db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
   ```

3. Find the administrators and their state:

   ```sql
   SELECT id, username, status, must_change_password, session_epoch FROM account WHERE role = 'ADMIN' ORDER BY id;
   ```

## 1. Login lockout (423 `auth/account-locked` or long 429 `auth/too-many-attempts`)

Failed logins are stored per peppered username hash; the lock of each (username, network) pair and the per-account
delay derive from them, so deleting the failures ends both at once. The hash is
`HMAC-SHA-256(EDUCORE_LOGIN_PEPPER, username)` in lower-case hex. Compute it on the host without printing the
pepper (the value is read from the backend container's environment and only the digest is shown):

```sh
docker compose exec educore-backend sh -c 'printf %s "$1" | openssl dgst -sha256 -hmac "$EDUCORE_LOGIN_PEPPER" | sed "s/^.*= //"' _ 'the-admin-username'
```

If the backend image has no `openssl`, run the same `printf ... | openssl dgst ...` pipeline on any machine that
has the pepper in its environment. Then:

```sql
DELETE FROM login_attempt WHERE username_hash = '<hex digest>' AND NOT success;
```

The lock ends with the next login attempt; no restart is needed.

## 2. Denied by an IP rule (403 `ipaccess/denied`)

```sql
SELECT id, kind, value, source, expires_at, created_by FROM ip_deny_rule ORDER BY id;
-- automatic rules after failed logins (login only, expire after 1 hour):
DELETE FROM ip_deny_rule WHERE source = 'AUTO';
-- a manual rule that covers the administrators' network (check the value first):
DELETE FROM ip_deny_rule WHERE id = <id>;
```

Each instance reloads the rules within `educore.ipaccess.deny-cache-ttl` (60 s). Automatic denials of IPv6 /64
networks are kept in memory only: restart the backend (`docker compose restart educore-backend`) to clear them.

## 3. Deactivated or pending-deletion administrator

```sql
UPDATE account
   SET status = 'ACTIVE', deleted_at = NULL, delete_after = NULL,
       session_epoch = session_epoch + 1, version = version + 1
 WHERE id = <admin id> AND status IN ('DEACTIVATED', 'PENDING_DELETION');
UPDATE refresh_token_family SET revoked_at = now() WHERE account_id = <admin id> AND revoked_at IS NULL;
UPDATE refresh_token SET revoked_at = now() WHERE account_id = <admin id> AND revoked_at IS NULL;
```

Incrementing `session_epoch` and revoking the families makes this a session boundary, like the API restore: any
session from before stays dead and the administrator signs in again.

## 4. Lost administrator password

Set a temporary password that must be changed at the next sign-in. Generate a bcrypt hash of a fresh random
password on a trusted machine (cost 12), for example with `htpasswd` from apache2-utils:

```sh
htpasswd -nbBC 12 "" 'a-long-random-temporary-password' | tr -d ':\n'
```

Store it with the `{bcrypt}` prefix, force the password change and end every session:

```sql
UPDATE account
   SET password = '{bcrypt}<hash>', must_change_password = true,
       session_epoch = session_epoch + 1, version = version + 1
 WHERE id = <admin id>;
UPDATE refresh_token_family SET revoked_at = now() WHERE account_id = <admin id> AND revoked_at IS NULL;
UPDATE refresh_token SET revoked_at = now() WHERE account_id = <admin id> AND revoked_at IS NULL;
```

Hand the temporary password over out of band. With it, the session can only change the password
(403 `account/password-change-required` elsewhere). Never paste the password or the hash into tickets or logs.

## 5. No administrator left at all

When no `ACTIVE` account with role `ADMIN` exists (for example all were purged), set
`EDUCORE_BOOTSTRAP_ADMIN_USERNAME` / `EDUCORE_BOOTSTRAP_ADMIN_PASSWORD` (a username that does not exist yet) and
restart the backend: `AdminBootstrap` creates a new ADMIN that must change its password. Remove the password
variable from the environment afterwards.

## 6. Afterwards

- Review `security_event` for the incident window: `AUTH_LOCKED`, `AUTH_LOGIN_FAILURE` bursts, `IP_RULE_CHANGED`,
  `ROLE_CHANGED`, `ACCOUNT_DELETED`, `ACCOUNT_PURGED`.
- If an ADMIN credential may be compromised, also rotate `EDUCORE_JWT_SECRET` (`docs/security/KEY_ROTATION.md`,
  emergency rotation) so every access token ends at once.
