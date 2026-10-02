# EduCore Abuse Cases

Version 1.1 · 2026-10-02 (status lines for release 1.0.0 added). User-story misuse cases per actor, each with the expected system behaviour and the
test that proves it (existing class name, or `GAP` when none exists). Companion to
[`THREAT_MODEL.md`](THREAT_MODEL.md) (risk ids) and [`ATTACK_CHAINS.md`](ATTACK_CHAINS.md) (chain ids).
Format: **As a** <actor>, **I abuse** <capability> **so that** <goal>; **the system must** <behaviour>.

## 1. Authenticated USER (student)

**UC-U1 — escalate to admin.** As a USER, I send `PUT /api/v1/admin/accounts/{myId}/role {"role":"ADMIN"}`,
or put `"role":"ADMIN"`/`{"role":0}` in a profile or create body, so that I gain admin rights. The system
must answer 403 for the admin route (role read from the DB, not the token) and ignore server-owned fields in
any body; strict Jackson rejects a number for the enum. **Test:** `PrivilegeEscalationIT`, `MassAssignmentIT`,
`ValidationIT`, `AuthorizationMatrixIT`.

**UC-U2 — read another student (IDOR).** As a USER, I call `/api/v1/admin/accounts/{otherId}/enrollments`, or
pass another `accountId` in an enrollment body, so that I see or change someone else's data. The system must
answer 403 on the admin route and bind `/me` routes to the principal only (`#accountId == principal.id`).
**Test:** `IdorIT`, `AuthorizationMatrixIT`, `PathVariantSecurityIT`.

**UC-U3 — enumerate the catalog of unpublished courses.** As a USER, I read `GET /api/v1/courses` to see
unpublished courses before launch. The system behaves as designed: members see unpublished courses (slug,
description, `published`); only the anonymous `/public/**` surface is restricted to published rows. No defect;
documented. **Test:** `PublicApiLeakIT` (public surface), `AuthorizationMatrixIT` row 11.

**UC-U4 — pump data via export.** As a USER, I call `GET /api/v1/me/export` in a tight loop to stress the
server. The system must allow one export per account per minute (429 after), return only my own data, and cap
security events. **Test:** `DataExportIT`.

**UC-U5 — bypass the forced password change.** As a USER created with a temporary password, I call the API
directly instead of the web UI so that I act without changing my password. The system **currently allows this**
(the flag is enforced only in the browser guard). Expected: a server scope filter should restrict me to
`/auth/password`, `/auth/me`, `/auth/logout`. **Status 1.0.0: FIXED.** `PasswordChangeRequiredScopeFilter` enforces exactly that scope (403 `account/password-change-required`). **Test:** `PasswordChangeRequiredScopeIT`, `AdminBootstrapIT`.

**UC-U6 — IPv6-rotate past the login throttle.** As a USER on a /64, I rotate source addresses to try many
passwords. Expected: the throttle and auto-deny key by /64. The system **currently keys the login throttle by
full address and never auto-denies IPv6**. **Status 1.0.0: FIXED.** The login throttle, the lockout and auto-deny key IPv6 by /64. **Test:** `LoginIpv6ThrottlingIT`, `CaffeineBucketLoginRateLimiterTest`, `AutoDenyIT`, `ForwardedForThrottlingIT`.

**UC-U7 — undo an erasure with a stolen token.** As a USER whose device is compromised, my attacker uses my
pre-deletion access token to call `POST /me/restore`. Expected: restore re-authenticates. The system
**currently allows restore in the pending-deletion scope without a password**. **Status 1.0.0: FIXED.** The deletion request ends every token at once (session epoch), and restore requires `{currentPassword}`. **Test:** `AccountLifecycleIT`.

## 2. Curious or over-reaching administrator

**UC-A1 — read everyone's data without a trace.** As an ADMIN, I page through `/admin/accounts` and student
details to browse personal data. The system must enforce ADMIN role; every *mutation* is audited, but
read-only listing is **not** audited (R-12). Expected for high-assurance: an `ACCOUNT_LIST_VIEWED` event.
**Test:** `AuditEventIT` (mutations); GAP for reads. **Status 1.0.0: OPEN** (BACKLOG B-098).

**UC-A2 — de-pseudonymise purged students.** As an ADMIN, I read `/admin/security-events`, note the
`purged:<hex>` pseudonyms, then log in with username `account:<id>` for each id and read the resulting
`details.usernameHash` to map pseudonyms back to ids. The system **currently exposes this** because the
pseudonym HMAC and the login username HMAC share a key and `usernameHash` is returned in the admin API.
Expected: domain-separated keys and no `usernameHash` in responses. **Status 1.0.0: FIXED** for the key (derived pseudonym key); `usernameHash` is still returned (BACKLOG B-083) but no longer equals a pseudonym. **Test:** `PseudonymDomainSeparationTest`.

**UC-A3 — override a data-subject erasure.** As an ADMIN, I restore an account whose owner requested deletion,
silently and without reason. The system records `ACCOUNT_RESTORED {from: PENDING_DELETION}` but does not
require a reason or notify the owner, and allows it even after the grace period until the nightly purge.
Expected: reason recorded, owner informed. **Test:** `AccountLifecycleIT` (restore works); GAP for policy
(R-16). **Status 1.0.0: OPEN** (BACKLOG B-101); an ADMIN restore now ends every earlier session of the account.

**UC-A4 — lock everyone out.** As a compromised or rogue ADMIN, I add deny rules covering the address space
and hard-delete other admins. The system must stop a rule that covers my own IP or a trusted proxy and protect
the last admin, but **permits two complementary ranges and deleting all other admins** with no second
approval. **Test:** `IpAccessControlIT`, `LastAdminGuardIT` (partial); GAP for two-admin control (R-09, AC-18). **Status 1.0.0: OPEN** (BACKLOG B-080); recovery SQL is in `docs/ops/RUNBOOK_ADMIN_RECOVERY.md`.

**UC-A5 — leak usernames through hard-delete.** As an ADMIN, I hard-delete with `?confirm=<username>`; the
username lands in nginx access logs after the DB forgot it. Expected: confirmation in a body, not the query
string. **Status 1.0.0: FIXED.** `POST /api/v1/admin/accounts/{id}/purge` takes `{confirm}` in the body, `mode=hard` is 400, and nginx access logs drop query strings. **Test:** `AccountLifecycleIT`, `AuthorizationMatrixIT` rows 74–75.

**UC-A6 — SSRF / internal scan via webhooks.** As an ADMIN, I point a subscription at internal or metadata
addresses and read the delivery errors. The system must refuse loopback, private, link-local, metadata,
CGNAT, NAT64 and mapped ranges and `*.localhost` (save-time and send-time, no redirects, body unread), but
**still reveals fine-grained reachability of public addresses**. **Test:** `WebhookSsrfGuardTest`,
`WebhookTransportTest` (blocked); GAP for the public-range oracle (R-08, AC-13). **Status 1.0.0: PARTIAL** (BACKLOG B-097).

## 3. Malicious webhook receiver

**UC-W1 — forge an EduCore event.** As a receiver, I craft a payload and send it to my own ingestion as if
from EduCore. The system signs `timestamp.body` with HMAC-SHA256 and a per-subscription 32-byte secret;
I cannot forge it without the secret, and replays outside 5 minutes are rejected by a conformant receiver.
**Test:** `WebhookSignatureTest`.

**UC-W2 — stall the dispatcher.** As a receiver, I trickle bytes or never respond so that EduCore's dispatcher
hangs and other deliveries starve. The system must apply connect/read 5 s, a 10 s deadline, one claim at a
time with a fencing token, and never read my body. **Test:** `WebhookTransportTest`, `WebhookRetryIT`,
`WebhookAdminIT`. Residual: DNS resolution of the pre-check runs outside the deadline (R-25); GAP. **Status 1.0.0: FIXED**: both lookups run inside the deadline (`WebhookTransportTest`, blocking-resolver cases).

**UC-W3 — replay a delivery.** As a receiver (or an on-path attacker), I resend a captured delivery. The
system reuses `X-EduCore-Delivery` on retries so I can de-duplicate; the timestamp tolerance bounds replay.
**Test:** `WebhookSignatureTest`, `WEBHOOKS.md` verification snippet.

## 4. Compromised administrator account

**UC-C1 — exfiltrate secrets.** As a thief of an ADMIN session, I read webhook subscriptions hoping for the
signing secret. The system never returns the secret after creation and stores it AES-256-GCM encrypted.
**Test:** `WebhookAdminIT` (secret shown once), `SecretCipher` (via `WebhookAdminIT`).

**UC-C2 — persist across a password reset.** As a thief, I keep my access token after the real admin changes
the password. The system revokes all refresh families on password change, so I get no new token; my current
access token dies in ≤15 min + 30 s. Emergency key rotation invalidates it at once. **Test:**
`AccessTokenResidualValidityIT`, `PasswordChangeIT`.

**UC-C3 — hostage-take.** Covered by UC-A4 / AC-18.

## 5. Insider with database read

**UC-I1 — reverse passwords.** As an insider reading `account`, I try to recover passwords. The system stores
bcrypt(strength 12) hashes only; refresh tokens are SHA-256 digests. Reversal is infeasible; this is a
property, not a test. Residual: the insider has the hashes for offline guessing of weak passwords (the policy
blocks the 10k-most-common list). **Test:** `PasswordHashingTest`, `PasswordPolicyTest`.

**UC-I2 — read webhook secrets.** As an insider, I read `webhook_subscription.secret_encrypted`. It is useless
without `EDUCORE_ENCRYPTION_KEY` (a separate secret, operator boundary). **Test:** `SecretCipher` behaviour via
`WebhookAdminIT`.

**UC-I3 — de-anonymise purged accounts.** As an insider, I read `security_event` pseudonyms and correlate with
old `ACCOUNT_CREATED` ids or backups. Pseudonyms are keyed, but UC-A2/AC-12 shows the key is shared with login
hashing; and an insider can simply read old dumps. The erasure guarantee degrades against an insider with
backup access (operator boundary) and is broken by AC-12. **Test:** GAP (R-21). **Status 1.0.0: FIXED** for AC-12 (`PseudonymDomainSeparationTest`); restores replay the erasure ledger (`ErasureLedgerRestoreIT`); old dumps age out after at most 56 days locally.

**UC-I4 — read erased CSV data.** As an insider, I read `csv_uploads/done` and the backup archives for the
names of purged students. The system **currently retains these indefinitely**. **Test:** GAP (R-05, AC-08). **Status 1.0.0: FIXED.** Processed files are deleted after the import, `failed/` after 7 days, purges scrub kept files, and `done/` is archived only on request. **Test:** `IngestionRetentionIT`.

**UC-I5 — database foothold escalation.** As an insider (or any SQL-level foothold), I use the connection's
superuser rights to `COPY ... TO PROGRAM` or alter the audit trail. The backend **currently connects as the
PostgreSQL superuser**. Expected: a least-privilege application role. **Test:** GAP (R-23, AC-15). **Status 1.0.0: FIXED** for the backend (DML-only runtime role); the backup sidecar still uses the owner (BACKLOG B-091). **Test:** `DatabaseRolesIT`.

## 6. Public-API scraper

**UC-S1 — scrape the whole catalog fast.** As a scraper, I hammer `/api/v1/public/courses` and the sitemaps.
The system applies 120/min per IP on `/public/**`, `Cache-Control: max-age=300` and strong ETags so
conditional requests are cheap; only published, public fields are returned. No account field can appear.
**Test:** `RateLimitIT`, `PublicApiIT`, `PublicApiLeakIT`.

**UC-S2 — find unpublished courses by slug guessing.** As a scraper, I request likely slugs at
`/public/courses/{slug}`. Unknown, unpublished and malformed slugs all return the same 404
`course/not-found`, and an old ETag after unpublish returns 404 not 304. **Test:** `PublicApiIT`,
`PublicApiLeakIT`.

**UC-S3 — saturate the rate-limit store with many /64s.** As a scraper on a /48, I spread requests across
65,536 /64 keys to push honest newcomers into the shared overflow bucket. The store admits rather than evicts,
so existing buckets survive, but honest newcomers degrade to 1,000/min shared (R-13). Expected: nginx
`limit_req` as a first layer. **Test:** `CaffeineRateLimitStoreTest` (admission); GAP for /48 budget. **Status 1.0.0: PARTIAL** (BACKLOG B-082).

## 7. Spammer of login, refresh and export

**UC-P1 — brute-force login.** As a spammer, I flood `POST /auth/login`. The system applies 10/min per IP
(429), locks an account after 5 failures (423), and AUTO-denies an IP after 20 failures in 10 min. **Test:**
`LockoutIT`, `AutoDenyIT`, `RateLimitIT`. Residual: username-targeted lockout (R-01, AC-01) and IPv6 rotation
(R-04, AC-06). **Status 1.0.0:** both residuals FIXED (`LockoutIT`, `LoginIpv6ThrottlingIT`).

**UC-P2 — weaponise the lockout.** As a spammer, I deliberately lock out known usernames (admin, student
numbers) with 5 wrong passwords each. The system **currently has no defence and no unlock path**. Expected:
per-(username, IP) locking and an admin unlock. **Test:** GAP (R-01, AC-01). **Status 1.0.0: FIXED.** Locks are per (username, network) pair with a progressive per-username delay, and `POST /api/v1/admin/accounts/{id}/unlock-login` exists. **Test:** `LockoutIT`, `LoginUnlockIT`.

**UC-P3 — refresh-token reuse flood.** As a spammer, I replay a refresh cookie many times. The first use
rotates it; the second is reuse and revokes the family, logging `AUTH_REFRESH_REUSE`. Single-flight on the
client and a family row lock on the server prevent double rotation. **Test:** `RefreshReuseDetectionIT`,
`AuthConcurrencyIT`. Residual: benign races also log reuse (R-07, AC-11; BACKLOG B-096).

**UC-P4 — oversized bodies.** As a spammer, I post large JSON or multipart bodies to anonymous endpoints. The
system caps non-multipart bodies at 64 KB before auth (413), multipart at 5 MB, headers at 16 KB, and nginx at
6 MB. **Test:** `ProblemDetailsIT`, `ImportUploadContainerIT`.

**UC-P5 — flood the public rate-limit then starve login.** Covered by AC-07 (load-balancer collapse) and
UC-S3.

## Traceability

Status at release 1.0.0: UC-U5, UC-U6, UC-U7, UC-A2 (key), UC-A5, UC-W2 (DNS), UC-I4, UC-I5 (backend) and
UC-P2 are fixed with the tests named above; UC-A1, UC-A3, UC-A4, UC-A6 and UC-S3 remain open or partial with the
backlog ids named above.

Every abuse case maps to a threat-model risk (where a gap exists) and, for multi-step ones, to a chain:
UC-U5→R-03/AC-04, UC-U6→R-04/AC-06, UC-U7→R-16/AC-10, UC-A2/UC-I3→R-21/AC-12, UC-A3→R-16, UC-A4→R-09/AC-18,
UC-A5→R-22/AC-14, UC-A6/UC-W2→R-08/R-25/AC-13, UC-I4→R-05/AC-08, UC-I5→R-23/AC-15, UC-S3→R-13, UC-P2→R-01/AC-01.
Blocked cases (UC-U1, UC-U2, UC-U4, UC-W1, UC-W3, UC-C1, UC-C2, UC-S1, UC-S2, UC-P1 core, UC-P3 core, UC-P4)
have named passing tests.
