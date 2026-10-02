# EduCore Attack Chains

Version 1.1 · 2026-10-02. Companion to [`THREAT_MODEL.md`](THREAT_MODEL.md) (risk ids `R-nn`),
[`ABUSE_CASES.md`](ABUSE_CASES.md) and [`ATTACK_RESULTS.md`](ATTACK_RESULTS.md). Every chain was checked by
reading the code at the cited lines; nothing was executed. Version 1.1 adds a status paragraph under each chain
heading for release 1.0.0. Line numbers in the analysis refer to the tree before the fix waves. Verdicts:

- **VERIFIED-BLOCKED**: the cited code stops the chain; the named test pins it.
- **PARTIALLY-BLOCKED**: the chain is narrowed but a usable variant remains.
- **OPEN**: the chain works as described against the current tree.
- **FIXED in 1.0.0**: closed by the security fix waves after this analysis; the status paragraph under the
  heading names the code and the tests. The analysis below it describes the tree before the fix.

Severity is the threat-model rating of the residual. "Test" names an existing class or proposes one (`GAP`).

---

## AC-01 Lock the administrator out with five requests — FIXED in 1.0.0 (was OPEN, Critical (R-01))

**Status (1.0.0).** Fixed in 1.0.0. The hard lock is per (username hash, client key) pair (`V23`, `auth/LoginAttemptService.java`); a per-username progressive delay (5 free failures, then 1 s doubling to 30 s, 429 `auth/too-many-attempts`) replaces the username-wide lock, and networks with a successful sign-in to the account in the last 30 days are exempt. `POST /api/v1/admin/accounts/{id}/unlock-login` clears failed attempts (`ACCOUNT_LOGIN_UNLOCKED`); break-glass SQL is in `docs/ops/RUNBOOK_ADMIN_RECOVERY.md`. The test proposed below exists: `LockoutIT` (7 cases, owner versus attacker network, delay 1/2/4/8/16/30/30 s) and `LoginUnlockIT` (2). Remaining: the delay can slow the owner on a network that never signed in before (BACKLOG B-086).

**Preconditions.** Any network position; one username (the bootstrap ADMIN name is an operator choice;
imported students' usernames are their student numbers, `ingestion/batch/ImportJobConfig.java:103`).

**Steps.**
1. `POST /api/v1/auth/login` with the victim's username and a wrong password, five times within 15 minutes.
   `auth/LoginAttemptService.java:43-56` derives the lock from the last `max-failures` (5) attempts of the
   username hash regardless of source IP; attempts rejected while locked are not recorded.
2. The victim now receives 423 `auth/account-locked` for 15 minutes (`auth/AuthService.java:118-125`).
3. Repeat step 1 once every 15 minutes: 20 requests per hour, below the per-IP throttle (10/min,
   `auth/CaffeineBucketLoginRateLimiter.java:46`) and far below the auto-deny threshold (20 failures in
   10 minutes, `ipaccess/IpAutoDenyService.java:107`).
4. Scale: one request every three minutes per victim username keeps any number of accounts locked from a
   single IP (10/min budget = 150 locked accounts per 15 minutes per IP).

**Impact.** Permanent denial of login for chosen users or for the whole student body (student numbers are
enumerable). No endpoint unlocks an account (`RBAC_MATRIX.md` has no unlock row); `AUTH_LOCKED` is audited
but nobody is alerted. Combined with AC-02 the operator cannot even reach the admin API from the campus.

**Why current code does not stop it.** The lock is keyed only by username (`AttemptLocks` + `LoginAttempt`
rows); the IP throttle and auto-deny are per attacker IP and sized for guessing, not for lockout abuse.

**Fix.** Lock per (username, client IP) first; apply the username-wide lock only above a higher threshold
(e.g. 20 failures) with exponential back-off instead of a fixed 15 minutes; never count attempts whose IP has
a recent successful login for that username; add an audited `POST /api/v1/admin/accounts/{id}/unlock`;
alert on `AUTH_LOCKED` bursts. **Test (GAP).** `LockoutIT`: five failures from IP A, then the correct
password from IP B must succeed (or at least B must not be locked after the change).

---

## AC-02 Poison a shared NAT, then exploit the missing break-glass — PARTIALLY-BLOCKED, Medium (R-02, R-09)

**Status (1.0.0).** Partly addressed in 1.0.0: the unlock endpoint and the break-glass runbook (`docs/ops/RUNBOOK_ADMIN_RECOVERY.md`, section 2 for deny rules) exist, and an ADMIN whose network signed in successfully is exempt from the login delay. The AUTO deny of a shared NAT still re-triggers hourly; it remains PARTIALLY-BLOCKED.

**Preconditions.** Attacker on the same public IP as the operators (school NAT, office).

**Steps.**
1. 20 wrong logins in 10 minutes (any usernames) → `IpAutoDenyService.deny()` writes an AUTO rule for the
   NAT address, 1 h (`ipaccess/IpAutoDenyService.java:118-134`).
2. Every login from the campus is 403 `ipaccess/denied` (`ipaccess/IpAccessControlFilter.java:102-110`).
3. After expiry, repeat. The attacker's further attempts while denied are refused before `AuthService`, so
   they do not extend the rule; the attacker simply waits for the hour and re-triggers.
4. An administrator with a live session can delete the rule (`IpDenyRuleService.delete`); an administrator
   who has to sign in first cannot. Combine with AC-01 against the admin username and nobody can sign in
   from anywhere.

**What blocks part of it.** AUTO rules refuse only `POST /auth/login` (refresh, sessions and the admin API
keep working, D-NEW-51); trusted proxies are never auto-denied; a MANUAL rule may not cover the caller
(`IpDenyRuleService.java:121-124`). **What remains.** Hourly re-trigger, no unlock path without a session,
no runbook naming the SQL (`DELETE FROM ip_deny_rule WHERE source = 'AUTO'`).

**Fix.** Exempt addresses with a recent successful ADMIN login from auto-deny; add the unlock endpoint of
AC-01 and an incident runbook in `docs/ops/`. **Test.** `AutoDenyIT` covers creation; GAP for "ADMIN behind
denied NAT can still sign in after a prior success".

---

## AC-03 Promote a development database to production and keep the demo administrator — FIXED in 1.0.0 (was OPEN, High (R-02))

**Status (1.0.0).** Fixed in 1.0.0. `config/DevSeedAccountGuard.java` refuses a production start while a seed username still carries its seed hash or seed student number; the remedy is in `docs/ops/UPGRADE.md`. Tests: `FlywayProfileSwitchIT` (refuses, refuses after a rehash, starts after the seed accounts are deleted), `DevSeedAccountGuardTest`.

**Preconditions.** A database that was ever started under the `dev` profile (the documented path:
D-NEW-05 and `application-prod.yml:9` `repeatable:missing` exist precisely to allow it).

**Steps.**
1. `R__dev_seed.sql:16-20` inserted `admin`, `ayberk` and `ali` with bcrypt hashes of the local demo password;
   `must_change_password` keeps its default `false`.
2. The operator switches `SPRING_PROFILES_ACTIVE=prod`. `ProdStartupGuard.java:60-64` only checks that the
   Flyway *locations* contain no seed; the rows are already there.
3. `AdminBootstrap.java:42` sees `existsByRole(ADMIN)` and creates nothing, so the configured bootstrap
   credentials are never used.
4. Anyone who knows the demo password (the repository history contained it; the old login page displayed
   the username, BACKLOG B-006) signs in as `admin` with full ADMIN rights.

**Impact.** Complete takeover with a public credential.

**Fix.** In `ProdStartupGuard` (or an `ApplicationRunner` in prod) refuse to start when
`flyway_schema_history` contains `R__dev_seed` or when any account holds a seed hash; set
`must_change_password = true` in the seed (still client-enforced only, see AC-05); make the dev→prod
promotion a documented migration that deletes seed accounts. **Test (GAP).** `ProdStartupGuardIT`: seeded
database + prod profile must fail to start.

---

## AC-04 Harvest the bootstrap password from the host and use it forever — FIXED in 1.0.0 (was OPEN, High (R-03))

**Status (1.0.0).** Fixed in 1.0.0. A `mustChangePassword` session gets the role-less authority `ACCOUNT_PASSWORD_CHANGE_REQUIRED`; `security/PasswordChangeRequiredScopeFilter.java` (both filter chains) allows only `GET /api/v1/auth/me`, `POST /api/v1/auth/password`, `/auth/refresh` and `/auth/logout` and answers 403 `account/password-change-required` otherwise. Tests: `PasswordChangeRequiredScopeIT` (3: API-created, imported and bootstrap accounts), `AdminBootstrapIT`, `ManagementEndpointSecurityIT`. The bootstrap password remains readable on the host until the container is recreated (operator boundary).

**Preconditions.** Read access to `.env`, `docker inspect educore-app`, shell history, or a CI log that
echoes the environment.

**Steps.**
1. `EDUCORE_BOOTSTRAP_ADMIN_PASSWORD` is passed as a container environment variable
   (`docker-compose.prod.yml:27`) and stays there for the container's lifetime.
2. The bootstrap account is created with `mustChangePassword = true` (`config/AdminBootstrap.java:56`).
3. `security/JwtAuthenticationFilter.java:71-86` and `SecurityConfig` never check the flag; only
   `frontend/app/features/auth/guards.tsx:21-23` redirects the browser.
4. `curl -d '{"username":…,"password":…}' /api/v1/auth/login` then any `/api/v1/admin/**` call works until the
   operator changes the password. The same holds for every temporary student password.

**Fix.** A `MustChangePasswordScopeFilter` after `JwtAuthenticationFilter` (same pattern as
`PendingDeletionScopeFilter`) that allows only `POST /auth/password`, `GET /auth/me`, `POST /auth/logout`;
drop the bootstrap variables from the running container after first start (one-off `docker compose run`,
as `UPGRADE.md` already does for Flyway). **Test (GAP).** `AuthorizationMatrixIT` row set for a
`mustChangePassword` principal.

---

## AC-05 Production refresh and logout always fail because `EDUCORE_SEO_BASE_URL` is never bound — FIXED in 1.0.0 (was OPEN, High (R-19))

**Status (1.0.0).** Closed in 1.0.0. The failure did not reproduce as described: Spring Boot binds `EDUCORE_SEO_BASE_URL` to `educore.seo.base-url` through its legacy dash-to-underscore mapping, and `EduCorePropertiesTest` now pins that. The binding was hardened anyway: an explicit `${EDUCORE_SEO_BASE_URL:...}` placeholder in `application.yml`, `ProdStartupGuard` requires an https origin that is not localhost, and `EnvironmentVariableBindingTest` requires a placeholder for every `EDUCORE_*` variable documented in `.env.example` or passed by compose. Tests: `EnvironmentVariableBindingTest`, `EduCorePropertiesTest`, `ProdStartupGuardIT`.

**Preconditions.** Any production deployment following `docs/ops/TLS.md`.

**Steps.**
1. `docker-compose.prod.yml:20` passes `EDUCORE_SEO_BASE_URL`. No `application*.yml` maps it: the only
   `educore.seo` keys with explicit `${…}` placeholders are absent (grep of `src/main/resources` for `seo`
   returns nothing). Spring's relaxed binding turns `EDUCORE_SEO_BASE_URL` into `educore.seo.base.url`, which
   is not `educore.seo.base-url` (that property's environment name is `EDUCORE_SEO_BASEURL`). P5 hit the same
   trap for `trusted-proxies` and fixed it by an explicit placeholder (`application.yml:151`).
2. `EduCoreProperties.Seo.baseUrl` keeps its default `http://localhost:3000` (`config/EduCoreProperties.java:247`).
3. `security/OriginVerifier.java:30-43` builds the allow-list from CORS origins (empty in prod) plus that
   default. Every browser `POST /api/v1/auth/refresh` and `/logout` from `https://<site>` carries
   `Origin: https://<site>` → 403 `auth/origin-rejected` (`auth/AuthController.java:92-96`).
4. Sessions die after 15 minutes; `/sitemap.xml` and `site-facts.baseUrl` advertise `http://localhost:3000`;
   the prerender build's `assertSameSite` (`frontend/react-router.config.ts`) fails against the real backend.

**Impact.** Availability of every production session; broken SEO surface. Not an attack, a certain failure
that also blocks the `logout` revocation path (users cannot end server sessions).

**Fix.** `application.yml`: `educore.seo.base-url: ${EDUCORE_SEO_BASE_URL:http://localhost:3000}`; extend
`EduCorePropertiesTest` with the exact OS variable name as done for `EDUCORE_IPACCESS_TRUSTED_PROXIES`;
`scripts/preflight.sh` must assert the bound value. **Test (GAP).** `EduCorePropertiesTest`.

---

## AC-06 Rotate inside an IPv6 /64 to spray passwords without limit — FIXED in 1.0.0 (was OPEN, Medium (R-04))

**Status (1.0.0).** Fixed in 1.0.0. The login limiter, the lockout and the auto-deny counter use one key, `ClientAddress.clientKey` (IPv4 exact, IPv4-mapped normalised, IPv6 as /64). The login limiter has its own admission store (overflow bucket, no eviction). IPv6 /64 networks that reach the auto-deny threshold are denied the login endpoint in memory. Tests: `LoginIpv6ThrottlingIT` (2, rotating hops inside one /64), `CaffeineBucketLoginRateLimiterTest` (4), `AutoDenyIT`. Remaining: IPv6 denials are per instance and not visible to ADMINs (B-085); IPv6 deny rules (B-050).

**Preconditions.** Native IPv6 reachability (the compose edge listens on IPv4 only today, so this applies
to future deployments or to hosts whose Docker daemon proxies IPv6; `ipv6-policy=ALLOW` is the default).

**Steps.**
1. The login throttle is keyed by `ClientIpResolver.resolve()` → `ClientAddress.canonical()`, the full
   128-bit address (`auth/AuthController.java:98-100`, `ipaccess/ClientAddress.java:118-131`), while the
   general limiter uses the /64 (`ratelimit/RateLimitFilter.java:96-98`).
2. One residential /64 gives 2^64 keys: ten attempts per address, next address. `CaffeineBucketLoginRateLimiter`
   never sees the same key twice.
3. `IpAutoDenyService.recordFailure` drops non-IPv4 addresses (`IpAutoDenyService.java:94-96`): no auto-deny.
4. The only remaining control is the per-username lock after five failures, so the attacker tries at most
   four passwords per username and moves on: credential stuffing across the whole student-number space.
5. Bonus: `CaffeineBucketLoginRateLimiter.java:28-31` uses size-based eviction (not admission as in
   `CaffeineRateLimitStore`), so flooding 100,000 keys evicts an exhausted IPv4 bucket as well.

**Fix.** Key the login limiter and the auto-deny counter by `ClientAddress.rateLimitKey()`; switch the login
limiter to the admission pattern; until B-050 lands, document `ipv6-policy=DENY` for IPv6-reachable
deployments. **Test (GAP).** `ForwardedForThrottlingIT` with a trusted proxy forwarding rotating `2001:db8::/64`
hops: the 11th attempt must be 429.

---

## AC-07 Everyone is one IP behind a load balancer — PARTIALLY-BLOCKED, High for such deployments (R-10)

**Status (1.0.0).** Still PARTIALLY-BLOCKED in 1.0.0. `scripts/preflight.sh` now exists but does not detect a collapsed client address; tracked as BACKLOG B-081.

**Preconditions.** A cloud load balancer, CDN or WAF in front of nginx.

**Steps.**
1. `infra/nginx/snippets/proxy-to-backend.conf:11` overwrites `X-Forwarded-For` with `$remote_addr` (the
   LB), on purpose, so a client cannot inject hops.
2. The backend sees one client address for every user: one 60/min anonymous bucket, one 10/min login
   bucket, one `login_attempt.ip`, one audit IP.
3. 20 wrong passwords by anyone → AUTO deny of the LB address → login refused for the whole site for an
   hour (AC-02 at Internet scale). A MANUAL rule for the LB is refused only if the LB is a trusted proxy,
   which it is not.

**What blocks part of it.** The design is correct when nginx is the first hop; `TLS.md` states it. **What
remains.** Nothing in the repository detects the misconfiguration; `scripts/preflight.sh` (P10) does not exist
yet.

**Fix.** Document the LB case; add `set_real_ip_from <lb-cidr>; real_ip_header X-Forwarded-For; real_ip_recursive on;`
as a commented block in `nginx.prod.conf`; preflight check that two requests from different sources yield
different `login_attempt.ip` values. **Test.** Manual deployment check; `ForwardedHeadersTomcatIT` already
covers the backend side.

---

## AC-08 Erased students survive in CSV folders and backups — FIXED in 1.0.0 (was OPEN, Medium (R-05))

**Status (1.0.0).** Fixed in 1.0.0. SUCCEEDED and PARTIAL snapshots are deleted right after the import (`educore.ingestion.retain-processed-days` = 0). `failed/` files and reports are kept at most `retain-failed-days` (7) and deleted by the hourly `ingestion/IngestionRetention.java`, which never follows links. After a purge commits, the student's lines are removed from files still kept, the account's `webhook_delivery` history is deleted, and `job_log_entry` masks equal to the account's own line are cleared. The backup sidecar archives `done/` only with `BACKUP_ARCHIVE_CSV=true`; `DATA_RETENTION.md` has the rows. Tests: `IngestionRetentionIT` (3), `IngestionDirectoryProtocolIT`, `StudentImportJobIT`. Dumps still hold the data until they age out, and restores are bounded by the erasure ledger (AC-09).

**Preconditions.** A student imported by CSV, later purged (`DELETE /me` + 30 days, or admin `mode=hard`).

**Steps.**
1. The import copies the file to `processing/` and finally `done/` or `failed/`
   (`ingestion/IngestionDirectories.java:179-183`); PARTIAL/FAILED runs also write `<name>.report.json`
   with masked rows.
2. `lifecycle/AccountPurger.java:79-112` deletes database rows and pseudonymises events; it never touches the
   filesystem, `job_log.file_name` or `imported_file.original_name`.
3. `infra/backup/bin/backup.sh:149` archives `done/` nightly (`csv-done-<ts>.tar.gz`, 14 days) and
   `pg_dump` keeps the account for 14 daily + 8 weekly dumps; the `done/` folder itself has no retention at
   all, so the archive is re-created every night from the same unbounded source.
4. Anyone with host or backup-volume access reads the full name and student number of every student ever
   imported, purged or not.

**Impact.** KVKK/GDPR erasure claim in `DATA_RETENTION.md` is not met for CSV-imported data.

**Fix.** `IngestionRetention` job: delete `done/*.csv` after 30 days (keep reports), `failed/` after 30
days; delete the CSV immediately after SUCCEEDED (the report has what an operator needs); add the row to the
retention table; backup archive then naturally ages out. Document that purge cannot reach old dumps and that
dump retention is the hard bound. **Test (GAP).** `IngestionDirectoryProtocolIT`: file older than the
retention is removed.

---

## AC-09 Restore an older backup and bring back deleted accounts and dead sessions — FIXED in 1.0.0 (was PARTIALLY-BLOCKED, Medium (R-06))

**Status (1.0.0).** Fixed in 1.0.0. Every purge writes keyed digests to `erasure_ledger` (`V33`) and an fsynced line to the append-only file on the `educore_erasure_ledger` volume, outside the dump. `scripts/backup/restore.sh` refuses without a ledger source and runs `post-restore.sh`: it re-inserts the ledger, revokes every refresh token and family, increments every session epoch, re-grants the runtime role and leaves a pending replay. Before serving, the backend (`ErasureLedgerReplay`) re-purges every restored account whose id digest is in the ledger. Tests: `ErasureLedgerRestoreIT` (2: real `pg_dump`/`pg_restore`; the old refresh cookie is 401 after the restore), `ErasureLedgerTest` (3); drill `scripts/backup/tests/restore-drill.sh`. Remaining: login attempts and AUTO deny rules revert to the dump time, and PENDING deliveries may be re-sent (receivers de-duplicate by `X-EduCore-Delivery`).

**Preconditions.** An operator running `scripts/backup/restore.sh` after an incident.

**Steps.**
1. `restore.sh:75` runs `pg_restore --clean --if-exists … --single-transaction`: every table is replaced by
   the dump.
2. Accounts purged after the dump reappear with their old password hashes (documented: re-run the purge).
3. `refresh_token` rows revoked after the dump come back with `revoked_at = NULL`; a cookie captured before
   the dump (or a browser that was logged out) authenticates again on `POST /auth/refresh`; families revoked by
   a password change are live again, with the *old* password hash.
4. `login_attempt`, AUTO `ip_deny_rule` rows, `imported_file` hashes and reuse-detection state are gone;
   PENDING `webhook_delivery` rows are dispatched again.

**What blocks part of it.** `DATA_RETENTION.md:109-112` tells the operator to re-run the purge and re-apply
hard deletes. **What remains.** Nothing says to revoke sessions or rotate the JWT key; access tokens signed
before the restore stay valid anyway (15 min).

**Fix.** Add to `BACKUP_RESTORE.md` step 6: execute the revocation SQL from `KEY_ROTATION.md:60-65`, rotate
`EDUCORE_JWT_SECRET`, re-run `AccountPurgeJob`, review `ip_deny_rule`, and mark PENDING deliveries FAILED or
accept duplicates. Consider restoring `refresh_token*` and `login_attempt` as empty tables. **Test.** Drill
checklist item; `verify-latest.sh` extension that reports live refresh tokens in the dump.

---

## AC-10 Stolen pre-deletion token revives an account; admin soft delete leaves refresh families alive — FIXED in 1.0.0 (was PARTIALLY-BLOCKED, Medium (R-16, R-20))

**Status (1.0.0).** Fixed in 1.0.0. `account.session_epoch` (`V22`) is copied into every access token (`sep`) and checked by `JwtAuthenticationFilter`. The deletion request, ADMIN soft delete, ADMIN restore and own restore increment it and revoke every refresh family, so the pre-deletion token of step (a) stops working at once and the cookie of step (b) is rejected. `POST /api/v1/me/restore` requires `{currentPassword}` (counted towards the lockout) and answers a new session. Tests: `AccountLifecycleIT` (14), `AuthorizationMatrixIT` row 71, `JwtTamperingIT`. Remaining: an ADMIN restore of a pending deletion still records no reason (B-101).

**Preconditions.** (a) An attacker who copied a USER's access token (XSS on a third-party page is not
possible with the CSP; a malware-infected device is) within the last 15 minutes before the victim ran
`DELETE /me`; or (b) an attacker who holds a victim's refresh cookie while an ADMIN deactivates and later
restores the victim.

**Steps (a).**
1. Victim runs `DELETE /api/v1/me` with the password; all families are revoked
   (`lifecycle/AccountLifecycleService.java:121`).
2. The already-issued access token still verifies; `JwtAuthenticationFilter.java:78-79` grants the
   pending-deletion scope, which allows `POST /api/v1/me/restore` (`security/PendingDeletionScopeFilter.java:38-41`).
3. `restoreOwn` (`AccountLifecycleService.java:132-145`) needs no password: the attacker cancels the
   erasure, then uses the still-valid token for `POST /auth/password`? No: the account is ACTIVE again but the
   token's 15 minutes end; the attacker needs the password for anything further. Net effect: the victim's
   erasure is undone silently; with the password the attacker simply signs in anyway.

**Steps (b).**
1. ADMIN soft-deletes the victim (`account/AccountAdminService.java:196-213`): status DEACTIVATED, **no**
   `refreshTokens.revokeAll` call. The rows stay valid for up to 14 days.
2. ADMIN restores (`AccountLifecycleService.java:179-192`, `reactivate` writes status only).
3. The attacker's old cookie refreshes successfully: the deactivation was not a session boundary.

**What blocks part of it.** DEACTIVATED accounts cannot authenticate or refresh while deactivated
(`ActiveAccount.mayAuthenticate`); `DELETE /me` does revoke. **What remains.** Soft delete and restore are
not session boundaries; restore has no re-authentication.

**Fix.** `softDelete` and both `restore` paths call `refreshTokens.revokeAll(accountId)`; `restoreOwn`
requires `currentPassword` like `requestDeletion`; record `details.reason` on `ACCOUNT_RESTORED`.
**Test (GAP).** `AccountLifecycleIT`: cookie issued before soft delete must be rejected after restore.

---

## AC-11 Two tabs and a logout turn into a false "token reuse" alarm — PARTIALLY-BLOCKED, Low (R-07)

**Status (1.0.0).** Unchanged in 1.0.0; tracked as BACKLOG B-096.

**Preconditions.** Normal use in two tabs; or a logout racing a refresh.

**Steps.**
1. Tab A logs out: family revoked (`RefreshTokenService.revokeFamilyOf`), `BroadcastChannel` message
   `ended` sent (`frontend/app/lib/api.ts:61-63`).
2. Tab B had already sent `POST /auth/refresh` with the same cookie before the message arrived.
3. Server: the family row lock serialises both; B's token now has `revokedAt != null`, and
   `RefreshTokenService.java:100-106` records `AUTH_REFRESH_REUSE` and "revokes the family" again, before
   line 107 would have noticed the family is revoked.
4. The audit trail accumulates reuse events from benign logouts (and from `DELETE /me`), so a real cookie
   theft is indistinguishable from noise.

**What blocks part of it.** Cross-tab single flight (`navigator.locks`, `api.ts:50-56`) and the server-side
family lock prevent *double rotation*; `AuthConcurrencyIT` ("logout-vs-rotation x15") proves at most one
succeeds. **What remains.** Event semantics.

**Fix.** Reorder: if `family.isRevoked()` → `Rejected` without event; only a revoked token in a live family is
reuse. **Test (GAP).** `RefreshReuseDetectionIT`: logout then refresh with the old cookie → 401 and no
`AUTH_REFRESH_REUSE` row.

---

## AC-12 De-pseudonymise purged accounts from the audit trail — FIXED in 1.0.0 (was OPEN, Medium (R-21))

**Status (1.0.0).** Fixed in 1.0.0. `lifecycle/Pseudonyms.java` uses a key derived from the pepper with a label (`HMAC(label, pepper)`), so a login for `account:<id>` no longer produces the pseudonym of account `<id>`. Test: `PseudonymDomainSeparationTest` (3). Remaining: pseudonyms written before the fix are not recomputed (`DATA_RETENTION.md`), and `details.usernameHash` is still returned to ADMINs (B-083).

**Preconditions.** ADMIN role (or database read access).

**Steps.**
1. `lifecycle/Pseudonyms.java:25` computes `purged:` + first 16 hex of `UsernameHasher.hash("account:" + id)`
   — the same HMAC key and function that hashes login usernames (`auth/AuthService.java:113`,
   `auth/UsernameHasher.java:54-62`). There is no domain separation.
2. A `LoginRequest.username` may contain a colon (`^` pattern only excludes control characters,
   `ROUTES.md` "LoginRequest"). The attacker posts a login for username `account:12`.
3. `CurrentPasswordCheck.recordFailure` stores `details.usernameHash = HMAC("account:12")` in
   `security_event` (`auth/CurrentPasswordCheck.java:105-106`), which `GET /api/v1/admin/security-events`
   returns verbatim (`SecurityEventResponse` passes `details`).
4. The first 16 hex characters equal the pseudonym of account 12. Iterating ids 1…N (10 logins per minute per
   IP, no lockout concern because the usernames do not exist) maps every `purged:<hex>` back to its id, and
   from there, through old exports, backups or `ACCOUNT_CREATED` events, to the person.

**Impact.** The pseudonymisation guarantee of `DATA_RETENTION.md:82-84` ("nobody without the pepper can map
an id") is false for administrators and for anyone reading `security_event`.

**Fix.** Separate keys or labels: `HMAC(pepper, "pseudonym:v1:" + id)` cannot collide with a username
because usernames are hashed as raw bytes — better, derive a distinct key (`HKDF(pepper, "pseudonym")`).
Also stop exposing `usernameHash` in the admin API (keep it in the row for purge matching). **Test (GAP).**
`AccountLifecycleIT`: hash of username `account:<id>` must differ from the pseudonym digest.

---

## AC-13 Read the webhook delivery log as a port scanner — PARTIALLY-BLOCKED, Low (R-08)

**Status (1.0.0).** Partly fixed in 1.0.0. Both DNS lookups (pre-check and connect-time) now run on a bounded 4-thread executor inside the request deadline, and an overrun ends the attempt as `timeout`; `WebhookTransportTest` has two blocking-resolver cases (R-25 closed). The public-range oracle remains (R-08, BACKLOG B-097), so the chain stays PARTIALLY-BLOCKED.

**Preconditions.** ADMIN role.

**Steps.**
1. Create or update a subscription to `https://<target>:<port>/` (`webhook/WebhookUrls.java:25-53` accepts
   any public host; IP literals in blocked ranges are refused at save time, names only at send time).
2. `POST /admin/webhooks/{id}/test` (5 per minute, `WebhookService.java:119-121`), or wait for a real event.
3. `GET /admin/webhooks/{id}/deliveries` returns `lastError` ∈ {`blocked-address`, `dns-failure`,
   `timeout`, `tls-failure`, `connection-failure`, `http-NNN`} and `responseCode`
   (`webhook/HttpClientWebhookTransport.java:100-123`, `WebhookDeliveryResponse.java:7-9`).
4. `connection-failure` = closed, `timeout` = filtered, `tls-failure` = open non-TLS, `http-NNN` = TLS service
   with its status: a classifier for any public address, from EduCore's egress IP and with EduCore's
   `User-Agent`.

**What blocks part of it.** RFC 1918, loopback, link-local, metadata, CGNAT, NAT64, mapped addresses and
`*.localhost` are refused twice (`WebhookAddressPolicy.java`, `GuardedDnsResolver.java`), redirects are off,
bodies unread (`WebhookSsrfGuardTest`, `WebhookTransportTest`). **What remains.** Public-range probing with
a fine oracle; DNS resolution of the pre-check (`HttpClientWebhookTransport.java:100`) runs *before* the
deadline is scheduled (line 109), so a slow authoritative server can hold the single dispatcher thread for
the JVM's resolver timeout per attempt (R-25).

**Fix.** Collapse admin-visible errors to `blocked`, `unreachable`, `rejected(status)`; keep detail in logs;
resolve inside the deadline (run the pre-check on the deadline executor with a bounded wait) or drop the
pre-check and rely on the client resolver; rate-limit subscription URL changes. **Test.**
`WebhookTransportTest` already covers slow headers; GAP for slow DNS.

---

## AC-14 Hard-delete confirmation leaks usernames into edge logs — FIXED in 1.0.0 (was OPEN, Low (R-22))

**Status (1.0.0).** Fixed in 1.0.0. The purge is `POST /api/v1/admin/accounts/{id}/purge` with `{"confirm": "<username>"}` in the body; `DELETE ...?mode=hard` answers 400 `request/invalid`. The nginx access logs of `frontend/nginx.conf` and `infra/nginx/nginx.prod.conf` are JSON with `$request_uri` and the referer cut at `?`, plus the User-Agent only. Both configurations pass `nginx -t`. A curl check with query, Cookie, Authorization and Referer canaries found none of them in the access log. Tests: `AccountLifecycleIT`, `AuthorizationMatrixIT` rows 74–75. Remaining: nginx's error log keeps the request line of failed upstream calls (B-093).

**Steps.**
1. `DELETE /api/v1/admin/accounts/{id}?mode=hard&confirm=<username>` (`account/AccountAdminController.java:108-120`).
2. nginx logs the request line for `location ^~ /api/` (`nginx.prod.conf:92`; only `/healthz` has
   `access_log off`), so the username of every purged person sits in the edge container's rotated logs
   (3×10 MB) and in any log shipper, after the database forgot it.
3. The backend's own log sanitiser never sees query strings of other containers.

**Fix.** Move `confirm` into a JSON body (the body is never logged), or log `$request` with the query string
stripped for `/api/`. **Test (GAP).** `ValidationIT` for the new body shape.

---

## AC-15 Backend connects as the PostgreSQL superuser — FIXED in 1.0.0 (was OPEN, Medium (R-23))

**Status (1.0.0).** Fixed for the backend in 1.0.0. `infra/postgres/app-role.sql` and `infra/postgres/init/01-roles.sh` create the runtime role `EDUCORE_DB_APP_USERNAME`, which has DML, sequence and default privileges only. Flyway migrates as the owner through `config/MigrationRoleFlywayConfig.java`. `ProdStartupGuard` requires both roles and refuses identical ones. Test: `DatabaseRolesIT` (9 forbidden statements through the application's own pool). Remaining: the backup sidecar still dumps as the owner (B-091).

**Steps.**
1. `docker-compose.yml:28` creates the database role from `EDUCORE_DB_USERNAME` as `POSTGRES_USER`, which the
   official image makes a superuser; `:69` hands the same credentials to the backend.
2. Any SQL-level foothold (a future injection, a compromised dependency with JDBC access, a Flyway migration
   from a tampered build) can `COPY … TO PROGRAM`, read `pg_authid`, alter the audit trail silently, or create
   extensions.
3. The backup sidecar reuses the same role (`BACKUP_RESTORE.md:62-64`).

**Why the guard does not help.** `ArchitectureTest` forbids concatenated queries, but least privilege is the
layer below it.

**Fix.** Create an application role with `CREATE, USAGE` on the schema and table privileges only (Flyway can
run as the owner at migration time, the app as a lesser role via a second `EDUCORE_DB_APP_USERNAME`), and a
read-only role for `pg_dump`. **Test (GAP).** `EduCoreApplicationIT` variant with a non-superuser role.

---

## AC-16 Startup recovery deletes a sibling instance's staged upload — FIXED in 1.0.0 (was PARTIALLY-BLOCKED, Low (R-24))

**Status (1.0.0).** Fixed in 1.0.0. `V34__upload_staging.sql` records owner, lease and state (STAGING/COMMITTED) of every upload in a row committed before the file is staged. Recovery takes over only expired leases, through a conditional delete, and handles unregistered pre-V34 files only once they are older than one lease. An empty publish is logged at WARN. Test: `ImportUploadIT` (8, including two two-owner cases).

**Preconditions.** Two backend instances sharing `csv_uploads/` (BACKLOG B-051 scenario), one restarting.

**Steps.**
1. Instance A runs `ImportUploadService.accept`: the file is written to `staging/` (`IngestionDirectories.stage`)
   and the `IMPORT_UPLOADED` event is written in the *same, still open* transaction (`ImportUploadService.java:64-67`).
2. Instance B starts and runs `IngestionRecovery.recoverStagedUploads()` (`IngestionRecovery.java:132-148`):
   for every staged token it counts committed `IMPORT_UPLOADED` rows; A's row is not committed yet → B deletes
   A's staged file.
3. A commits; `afterCommit` → `publishStaged` finds nothing (`Optional.empty()`), logs nothing (the
   `Optional` is ignored), and the operator sees a 202 for an import that never happens.

**What blocks part of it.** Single-instance deployments never hit it; the window is the upload transaction.
**Fix.** Only discard staged files older than the lease (2 min), or write a `.committed` marker; log the
empty `publishStaged` result at WARN. **Test (GAP).** `ImportUploadIT` with a concurrent recovery call.

---

## AC-17 Lease expiry does not fence chunk writes — FIXED in 1.0.0 (was PARTIALLY-BLOCKED, Low (R-11))

**Status (1.0.0).** Fixed in 1.0.0 (integrity). Every chunk takes a shared transaction-scoped advisory lock, then checks that the run is open, owned and leased (`ingestion/IngestionFence.java`). Owner and recovery closes are conditional updates under the exclusive lock, so a close waits for chunks in flight and later chunks fail with `LeaseLostException`. The heartbeat no longer revives an expired lease. Test: `IngestionFencingIT` (3). Remaining: the heartbeat still shares the scheduler, so a long import can be interrupted needlessly (B-092).

**Steps.**
1. The owning instance's heartbeat (`IngestionInstance.java:41-44`) shares the 3-thread scheduler with the
   poller that runs whole import jobs synchronously, the webhook dispatcher and three more jobs
   (`application.yml:53-59`). Two long imports plus the dispatcher can starve it for more than the 2-minute
   lease.
2. `IngestionRecovery.recover()` (same instance, every 60 s) closes the run as FAILED/`INTERRUPTED`
   (`IngestionLedger.failExpired`) and moves the snapshot (`IngestionDirectories.finish`).
3. The batch step keeps reading the open file handle and `PreparedRowWriter.java:33` keeps saving accounts;
   nothing checks the lease.
4. `IngestionService.complete()` finds the run closed and returns (`IngestionService.java:199-202`): no
   counts, no webhook, no report; the ledger says nothing was written although hundreds of accounts exist.

**Fix.** Heartbeat on its own executor; `PreparedRowWriter` (or a `ChunkListener`) re-reads `lease_until`
and `status` and throws when the run was closed; recovery skips runs owned by the current instance id.
**Test (GAP).** `IngestionDirectoryProtocolIT` with a paused heartbeat.

---

## AC-18 A single compromised ADMIN takes the system hostage — OPEN, Medium (R-09)

**Status (1.0.0).** Partly addressed in 1.0.0: the purge confirmation moved into the request body and the recovery SQL is documented in `docs/ops/RUNBOOK_ADMIN_RECOVERY.md`. There is still no second approval; the chain remains OPEN (BACKLOG B-080).

**Preconditions.** One ADMIN credential (phished, or the demo admin of AC-03, or the bootstrap of AC-04).

**Steps.**
1. Create two MANUAL deny rules covering the whole address space except the attacker's own IP, each passing
   the self-deny and trusted-proxy guards (`ipaccess/IpDenyRuleService.java:121-129` only rejects a rule that
   covers the *caller's* address or a trusted proxy, so two complementary ranges both pass).
2. Every other operator and student is refused at `IpAccessControlFilter` with 403 `ipaccess/denied`.
3. Hard-delete every other ADMIN with `?mode=hard&confirm=<their username>` (`AccountLifecycleService.java:154-170`);
   the last-ADMIN guard only protects the *last* one, and the attacker is still active.
4. No second approval is required and no runbook documents the recovery SQL.

**Impact.** Full lock-out of everyone but the attacker; recovery needs direct database access.

**Fix.** Require two-ADMIN confirmation for a deny rule broader than /24 and for hard-deleting an ADMIN;
alert on `IP_RULE_CHANGED`/`ROLE_CHANGED`/`ACCOUNT_PURGED` bursts; ship an incident runbook with the
`DELETE FROM ip_deny_rule` and reactivation SQL. **Test (GAP).** `IpAccessControlIT`: two complementary
MANUAL rules are rejected, or the runbook query is validated.

---

## AC-19 CSV import to job log to webhook: a cross-boundary PII walk — VERIFIED-BLOCKED, Low

**Preconditions.** Host write access to the inbox, plus a webhook receiver the attacker controls.

**Steps and why each hop is blocked.**
1. Drop a CSV whose rows carry names and student numbers. The batch records skipped rows with
   `PiiMasker.mask` (`ingestion/batch/RowSkipRecorder.java:71-73`): `Ayşe,Yılmaz,20230017` → `A***,Y***,2***`.
2. `GET /admin/job-logs/{id}/entries` returns only the masked line; `job_log.file_name` keeps the real name
   but the endpoint is ADMIN-only.
3. `IngestionService.publish` builds the webhook payload from ids, kind, status and counts only — no file name,
   no row (`ingestion/IngestionService.java:239-254`); `import.completed`/`import.failed` reach the receiver
   without personal data (`WEBHOOKS.md` event table).

**Residual.** The file itself in `done/` and `job_log.file_name` are not masked — that is AC-08, not this
path. **Test.** `StudentImportJobIT` ("PII canary across batch_* tables and logs"), `FileNamesAndMaskingTest`.

---

## AC-20 Log injection / request-id forgery to forge audit lines — PARTIALLY-BLOCKED, Low (R-15)

**Status (1.0.0).** Unchanged in 1.0.0; tracked as BACKLOG B-100.

**Steps.**
1. Send `X-Request-Id` with CR/LF or a crafted value. `security/RequestIdFilter.java:66-77` accepts it only
   when it matches `[A-Za-z0-9._-]{1,64}`; anything else becomes a fresh UUID, so CR/LF cannot be injected
   into a log line, and `LogSanitizer` strips separators from user strings (`common/logging/LogSanitizer.java`).
2. Remaining variant: a client may still choose a *valid* id that equals another request's id, so
   `security_event.request_id` and the `correlationId` in a 5xx body can collide, letting an attacker claim a
   victim's correlation id (R-15). It cannot forge the event content, only the id field.

**Fix.** Accept the incoming `X-Request-Id` only from the trusted proxy, or always append a server-generated
suffix. **Test.** `RequestIdFilterTest` covers rejection of unsafe ids; GAP for collision handling.

---

## Coverage summary

| Chain | Verdict (analysis) | Status 1.0.0 | Severity | Risk | Evidence or follow-up |
|---|---|---|---|---|---|
| AC-01 username lockout DoS | OPEN | FIXED | Critical | R-01 | `LockoutIT`, `LoginUnlockIT`; B-086 |
| AC-02 NAT poisoning + no break-glass | PARTIAL | PARTIAL (runbook added) | Medium | R-02, R-09 | `RUNBOOK_ADMIN_RECOVERY.md`; B-080 |
| AC-03 dev seed promoted to prod | OPEN | FIXED | High | R-02 | `FlywayProfileSwitchIT`, `DevSeedAccountGuardTest` |
| AC-04 bootstrap/temp password is a full credential | OPEN | FIXED | High | R-03 | `PasswordChangeRequiredScopeIT`, `AdminBootstrapIT` |
| AC-05 SEO base-url unbound breaks prod refresh/logout | OPEN | FIXED (did not reproduce; hardened) | High | R-19 | `EnvironmentVariableBindingTest`, `ProdStartupGuardIT` |
| AC-06 IPv6 rotation bypasses login throttle | OPEN | FIXED | Medium | R-04 | `LoginIpv6ThrottlingIT`, `CaffeineBucketLoginRateLimiterTest`; B-085 |
| AC-07 LB collapses every client to one IP | PARTIAL | PARTIAL | High (such deploys) | R-10 | B-081 |
| AC-08 erased students persist in CSV/backups | OPEN | FIXED | Medium | R-05 | `IngestionRetentionIT` |
| AC-09 restore resurrects accounts/sessions | PARTIAL | FIXED | Medium | R-06 | `ErasureLedgerRestoreIT`, `ErasureLedgerTest`, restore drill; B-090, B-094 |
| AC-10 pre-deletion token restore; soft delete keeps families | PARTIAL | FIXED | Medium | R-16, R-20 | `AccountLifecycleIT`; B-101 |
| AC-11 benign refresh race = reuse alarm | PARTIAL | PARTIAL | Low | R-07 | B-096 |
| AC-12 de-pseudonymise via shared HMAC | OPEN | FIXED | Medium | R-21 | `PseudonymDomainSeparationTest`; B-083 |
| AC-13 webhook delivery log as port scanner | PARTIAL | PARTIAL (DNS deadline fixed) | Low | R-08, R-25 | `WebhookTransportTest`; B-097 |
| AC-14 hard-delete confirm leaks to nginx logs | OPEN | FIXED | Low | R-22 | `AccountLifecycleIT`, nginx canary check; B-093 |
| AC-15 backend runs as DB superuser | OPEN | FIXED (backend) | Medium | R-23 | `DatabaseRolesIT`; B-091 |
| AC-16 recovery deletes a sibling's staged upload | PARTIAL | FIXED | Low | R-24 | `ImportUploadIT` |
| AC-17 lease expiry does not fence chunk writes | PARTIAL | FIXED | Low | R-11 | `IngestionFencingIT`; B-092 |
| AC-18 one compromised ADMIN hostage-takes | OPEN | OPEN (recovery documented) | Medium | R-09 | B-080 |
| AC-19 CSV→joblog→webhook PII walk | BLOCKED | BLOCKED | Low | — | `StudentImportJobIT`, `FileNamesAndMaskingTest` |
| AC-20 request-id forgery | PARTIAL | PARTIAL | Low | R-15 | B-100 |
