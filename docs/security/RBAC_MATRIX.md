# RBAC Matrix

Status: enforced since phase P3. This document is the source of truth for authorization; the code
implements it and `src/test/java/com/educore/authz/AuthorizationMatrixIT.java` executes every cell below.
Route shapes (request and response bodies) are in [`docs/api/ROUTES.md`](../api/ROUTES.md).

## Roles and columns

| Column | Caller |
|---|---|
| ADMIN | Bearer access token of an active account with role `ADMIN`. |
| USER-self | Bearer access token of an active `USER` account; the `{accountId}` in the path (if any) is the caller's own id. |
| USER-other | Bearer access token of an active `USER` account; the `{accountId}` in the path (if any) is another account's id. For routes without an account in the path, USER-self and USER-other send the same request. |
| anonymous | No `Authorization` header (or an invalid/expired token, which is treated the same way). |

The role is read from the database on every request (`JwtAuthenticationFilter`); the `roles` claim of the
token is never trusted. A `DEACTIVATED` (soft-deleted) account is anonymous; a `PENDING_DELETION` account has
the restore-only scope and an account with `mustChangePassword` the password-change scope, both described below.
An access token whose session epoch (`sep` claim) differs from the account's `session_epoch` is treated as no
token at all (401): the owner's deletion request, an ADMIN soft delete and both restores increment the epoch.

Cell values: the HTTP status of a valid request. `401` = not authenticated, `403` = authenticated but not
allowed, `NA` = the route cannot address another account (the account is always the caller).

Error bodies (since P4): every `401` and `403`, on the application port and the management port, is an
RFC 9457 problem (`application/problem+json`) in the shape described in
[`ROUTES.md` › Errors](../api/ROUTES.md#errors): `401` has code `auth/unauthenticated`, `403` has code
`auth/access-denied` (`auth/origin-rejected` for a refused `Origin` on refresh/logout). The statuses in the
cells are unchanged.

## Enforcement layers

1. URL rules in `SecurityConfig` (application port):
   `POST /api/v1/auth/login|refresh|logout` and `GET`/`HEAD` on `/api/v1/public/**`, `/sitemap.xml` and
   `/sitemap-courses-*.xml` are anonymous;
   `/api/v1/admin/**` requires `ROLE_ADMIN`; every other path requires authentication (`/ws/**` no longer
   exists and is not listed). The management port (actuator) has its own chain: `health` is anonymous,
   every other endpoint requires `ROLE_ADMIN`.
2. Method security (`@EnableMethodSecurity`): every admin controller and admin service carries
   `@PreAuthorize("hasRole('ADMIN')")`; enrollment service methods carry
   `@PreAuthorize("#accountId == principal.id or hasRole('ADMIN')")`. The principal is the typed
   `AuthenticatedUser(id, username, role)`, never the `Account` entity.
3. Business guards (409 problems, `AccountAdminService`): an ADMIN cannot change their own role or delete
   their own account, and the last active ADMIN can be neither demoted nor deleted (soft delete included).
   The check locks every active ADMIN row (`SELECT ... FOR UPDATE`) so two concurrent demotions cannot
   remove the last ADMIN.
4. DTO boundary: request bodies are records without `id`, `role`, `deleted`, `password` or
   `mustChangePassword` (except `ChangeRoleRequest.role`); unknown JSON fields are ignored. Responses
   never contain password hashes, `deleted`, `mustChangePassword` or entity graphs.

## Matrix — application port

Machine-readable source: `src/test/resources/rbac-matrix.csv`. Every row below mirrors one CSV row
(`AuthorizationMatrixIT` fails when they differ, when a mapped `/api/**` handler has no row, when a handler
outside `/api/**` (other than `/error`) has no `SITE` row, or when a row has no handler). `NA` = the route cannot address another account, `OPEN` = permitted by the URL rule.

| # | Method | Route | ADMIN | USER-self | USER-other | anonymous | Note |
|---|---|---|---|---|---|---|---|
| 01 | POST | `/api/v1/auth/login` | 200 | 200 | 200 | 200 |  |
| 02 | POST | `/api/v1/auth/refresh` | 200 | 200 | 200 | 200 | refresh cookie and an allowed `Origin` |
| 03 | POST | `/api/v1/auth/logout` | 204 | 204 | 204 | 204 | allowed `Origin` |
| 04 | GET | `/api/v1/auth/me` | 200 | 200 | NA | 401 |  |
| 05 | POST | `/api/v1/auth/password` | 200 | 200 | NA | 401 |  |
| 06 | GET | `/api/v1/me` | 200 | 200 | NA | 401 |  |
| 07 | PUT | `/api/v1/me` | 200 | 200 | NA | 401 |  |
| 08 | GET | `/api/v1/me/enrollments` | 200 | 200 | NA | 401 |  |
| 09 | POST | `/api/v1/me/enrollments` | 201 | 201 | NA | 401 |  |
| 10 | DELETE | `/api/v1/me/enrollments/{courseId}` | 204 | 204 | NA | 401 |  |
| 11 | GET | `/api/v1/courses` | 200 | 200 | 200 | 401 |  |
| 12 | GET | `/api/v1/weather` | 200 | 200 | 200 | 401 |  |
| 13 | GET | `/api/v1/admin/accounts` | 200 | 403 | 403 | 401 |  |
| 14 | GET | `/api/v1/admin/accounts/students` | 200 | 403 | 403 | 401 |  |
| 15 | POST | `/api/v1/admin/accounts/students` | 201 | 403 | 403 | 401 |  |
| 16 | PUT | `/api/v1/admin/accounts/{accountId}` | 200 | 403 | 403 | 401 |  |
| 17 | DELETE | `/api/v1/admin/accounts/{accountId}` | 204 | 403 | 403 | 401 | soft delete only (`mode=soft`) |
| 18 | PUT | `/api/v1/admin/accounts/{accountId}/role` | 200 | 403 | 403 | 401 |  |
| 19 | GET | `/api/v1/admin/accounts/{accountId}/enrollments` | 200 | 403 | 403 | 401 |  |
| 20 | POST | `/api/v1/admin/accounts/{accountId}/enrollments` | 201 | 403 | 403 | 401 |  |
| 21 | DELETE | `/api/v1/admin/accounts/{accountId}/enrollments/{courseId}` | 204 | 403 | 403 | 401 |  |
| 22 | POST | `/api/v1/admin/courses` | 201 | 403 | 403 | 401 |  |
| 23 | PUT | `/api/v1/admin/courses/{courseId}` | 200 | 403 | 403 | 401 |  |
| 24 | DELETE | `/api/v1/admin/courses/{courseId}` | 204 | 403 | 403 | 401 |  |
| 25 | GET | `/api/v1/admin/ip-allocations` | 200 | 403 | 403 | 401 | student IP allow-list; was `/admin/ip-rules` before P5 (D-09) |
| 26 | POST | `/api/v1/admin/ip-allocations` | 201 | 403 | 403 | 401 |  |
| 27 | DELETE | `/api/v1/admin/ip-allocations/{ipAllocationId}` | 204 | 403 | 403 | 401 |  |
| 28 | GET | `/api/v1/admin/job-logs` | 200 | 403 | 403 | 401 |  |
| 29 | DELETE | `/api/v1/admin/job-logs` | 204 | 403 | 403 | 401 | query `ids=1,2,3` |
| 30 | GET | `/api/v1/admin/security-events` | 200 | 403 | 403 | 401 |  |
| 31 | GET | `/api/v1/public/**` | OPEN | OPEN | OPEN | OPEN | URL rule as a whole (GET and HEAD only; other methods need authentication); its routes are rows 40–42 |
| 32 | GET | `/api/v1/accounts/students` | 404 | 404 | 404 | 401 | sample of every removed pre-P3 route and any other unmapped path |
| 40 | GET | `/api/v1/public/courses` | 200 | 200 | 200 | 200 | published courses only; public fields only (P9) |
| 41 | GET | `/api/v1/public/courses/{slug}` | 200 | 200 | 200 | 200 | 404 `course/not-found` for an unknown or unpublished slug |
| 42 | GET | `/api/v1/public/site-facts` | 200 | 200 | 200 | 200 | organisation facts (`educore.seo.*`) |
| 43 | GET | `/sitemap.xml` | 200 | 200 | 200 | 200 | outside `/api/**` (CSV kind `SITE`); no `X-Robots-Tag`; a sitemap index above `educore.seo.sitemap-max-urls` |
| 44 | GET | `/sitemap-courses-{file}.xml` | 200 | 200 | 200 | 200 | sitemap file `n` (CSV kind `SITE`); 404 outside 1..number of files |
| 50 | GET | `/api/v1/admin/ip-rules` | 200 | 403 | 403 | 401 | request-level IP deny rules (P5), paginated |
| 51 | POST | `/api/v1/admin/ip-rules` | 201 | 403 | 403 | 401 | 409 `ip-rule/self-deny` / `ip-rule/trusted-proxy` |
| 52 | GET | `/api/v1/admin/ip-rules/{ipRuleId}` | 200 | 403 | 403 | 401 |  |
| 53 | PUT | `/api/v1/admin/ip-rules/{ipRuleId}` | 200 | 403 | 403 | 401 | same 409 guards as row 51 |
| 54 | DELETE | `/api/v1/admin/ip-rules/{ipRuleId}` | 204 | 403 | 403 | 401 |  |
| 60 | GET | `/api/v1/admin/job-logs/{jobLogId}/entries` | 200 | 403 | 403 | 401 | row-level import entries (P6) |
| 61 | POST | `/api/v1/admin/imports` | 202 | 403 | 403 | 401 | multipart `file`; written into the ingestion inbox (P6) |
| 62 | GET | `/api/v1/admin/webhooks` | 200 | 403 | 403 | 401 | webhook subscriptions (P6) |
| 63 | POST | `/api/v1/admin/webhooks` | 201 | 403 | 403 | 401 | answers the signing secret once |
| 64 | GET | `/api/v1/admin/webhooks/{webhookId}` | 200 | 403 | 403 | 401 |  |
| 65 | PUT | `/api/v1/admin/webhooks/{webhookId}` | 200 | 403 | 403 | 401 |  |
| 66 | DELETE | `/api/v1/admin/webhooks/{webhookId}` | 204 | 403 | 403 | 401 | deletes its deliveries too |
| 67 | POST | `/api/v1/admin/webhooks/{webhookId}/test` | 202 | 403 | 403 | 401 | queues a `webhook.test` delivery |
| 68 | GET | `/api/v1/admin/webhooks/{webhookId}/deliveries` | 200 | 403 | 403 | 401 |  |
| 70 | DELETE | `/api/v1/me` | 202 | 202 | NA | 401 | deletion request (P7); body `{currentPassword}`; 409 `account/last-admin` for the last active ADMIN |
| 71 | POST | `/api/v1/me/restore` | 200 | 200 | NA | 401 | body `{currentPassword}` (fix1); cancels a pending deletion and answers a new session; for an active account nothing changes (new session); allowed in the restore-only scope |
| 72 | GET | `/api/v1/me/export` | 200 | 200 | NA | 401 | data export (P7); 1 per account and minute, then 429 `rate-limit/exceeded` |
| 73 | POST | `/api/v1/admin/accounts/{accountId}/restore` | 200 | 403 | 403 | 401 | reactivates a `DEACTIVATED` or `PENDING_DELETION` account (P7); every earlier session stays ended |
| 74 | POST | `/api/v1/admin/accounts/{accountId}/purge` | 204 | 403 | 403 | 401 | hard delete, body `{confirm: <username>}` (fix1, replaces `DELETE ?mode=hard&confirm=`) |
| 75 | POST | `/api/v1/admin/accounts/{accountId}/unlock-login` | 204 | 403 | 403 | 401 | clears the account's failed logins (fix1) |

Notes:
- Rows 8–10 operate on the caller's own account only; an `accountId` sent in the body is ignored. Rows
  19–21 are the ADMIN equivalents for any account. Both paths go through the same `EnrollmentService`
  methods, which enforce `#accountId == principal.id or hasRole('ADMIN')` a second time.
- Row 18 additionally answers 409 when the target is the caller (`account/self-role-change`) or when the
  change would leave no active ADMIN (`account/last-admin`). Row 17 answers 409 for the caller's own account
  (`account/self-delete`) and for the last active ADMIN (`account/last-admin`).
- USER-self on rows 16–21 is the privilege-escalation case (a USER addressing their own account through an
  admin route); it is denied like USER-other.
- Row 17 accepts only `mode=soft` (default; `ACTIVE` -> `DEACTIVATED`, restorable by row 73, every session of the
  account ended); any other `mode` (the former `mode=hard`) is 400 `request/invalid`. Row 74 purges at once when
  `confirm` in the JSON body equals the account's username (400 `account/confirmation-mismatch` otherwise), so the
  username never appears in a request line (R-22). Both keep the self and last-ADMIN guards.
- Rows 70–72 always act on the caller. Rows 70 and 71 re-authenticate with the current password (wrong password:
  400 `auth/invalid-current-password`, counted towards the login lockout), so a token stolen before a deletion
  request can neither restore the account nor outlive the request (R-16, R-20).
- Row 75 deletes the failed login attempts of the account: every (username, client) lock and the per-account
  progressive delay end at once (R-01); recovery without any ADMIN session: `docs/ops/RUNBOOK_ADMIN_RECOVERY.md`.

## Restore-only scope during the deletion grace period (P7)

An account whose owner requested deletion (`status = PENDING_DELETION`) can still sign in until `deleteAfter`
(login and refresh answer as usual; `user.status` is `PENDING_DELETION`). Its bearer token authenticates with
the single authority `ACCOUNT_PENDING_DELETION` and no role, and `PendingDeletionScopeFilter` (right after
`JwtAuthenticationFilter`) lets only these requests through:

| Method | Route | Answer |
|---|---|---|
| GET | `/api/v1/me` | 200 `Profile` with `status` and `deleteAfter` |
| POST | `/api/v1/me/restore` | 200 `Profile` (`ACTIVE` again) |
| POST | `/api/v1/auth/logout` | 204 (anonymous by URL rule anyway) |

Every other request of such an account, on any path (including `/api/v1/auth/me`, unknown paths and path
variants such as a trailing slash), answers 403 `account/pending-deletion`; on the management port it has no
role and gets 403 `auth/access-denied`. After `deleteAfter` the account can no longer authenticate (401) and
`AccountPurgeJob` purges it. `DEACTIVATED` accounts never authenticate. Covered by `AccountLifecycleIT`.

The access token used to request the deletion stops working at once (session epoch); the restore-only session is
obtained by signing in during the grace period. Restoring needs the current password (row 71) and answers a new
full session; the restore-only session ends with it.

## Password-change scope (`mustChangePassword`, fix1)

An active account flagged `mustChangePassword` (API-created and CSV-imported students with their temporary
password, the bootstrap ADMIN with `EDUCORE_BOOTSTRAP_ADMIN_PASSWORD`) authenticates with the single authority
`ACCOUNT_PASSWORD_CHANGE_REQUIRED` and no role. `PasswordChangeRequiredScopeFilter` (right after
`JwtAuthenticationFilter`, in the application chain and in the management chain) lets only these requests through:

| Method | Route | Answer |
|---|---|---|
| GET | `/api/v1/auth/me` | 200 `UserView` with `mustChangePassword: true` |
| POST | `/api/v1/auth/password` | 200 new session; the flag is cleared and the role applies from then on |
| POST | `/api/v1/auth/refresh` | 200 (the refreshed token keeps the scope while the flag is set) |
| POST | `/api/v1/auth/logout` | 204 |

Every other request, on any path of the application port (including `GET /api/v1/me`, admin routes, unknown paths
and path variants) and on the management port, answers 403 `account/password-change-required`. The role is read
from the database on every request, so the scope ends with the password change. Covered by
`PasswordChangeRequiredScopeIT`, `AdminBootstrapIT` (bootstrap ADMIN end to end) and
`ManagementEndpointSecurityIT`.

## Matrix — management port (`management.server.port`, not published by docker-compose)

| # | Method | Route | ADMIN | USER | anonymous | Test |
|---|---|---|---|---|---|---|
| M1 | GET | `/actuator/health` | 200 | 200 | 200 | `ManagementEndpointSecurityIT` |
| M2 | GET | `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus` | 200 | 403 | 401 | `ManagementEndpointSecurityIT` |

## Capabilities from the program baseline scheduled for later phases

These capabilities have no endpoint yet; their rows are added here when the endpoint is built. Export of own
data and the own deletion request were built in P7 (rows 70–72).

| Capability | ADMIN | USER-self | USER-other | anonymous | Phase |
|---|---|---|---|---|---|
| Admin password reset for an account (BACKLOG B-016) | allow | deny | deny | deny | not scheduled |

## Audit

Every ADMIN mutation writes one `security_event` row through `AuditService` with `actor_account_id`,
`target_account_id` (when the target is an account), client `ip` and `request_id` (`X-Request-Id`).
`details` holds ids, enum values and field names only — never names, student numbers, IP addresses of
students, passwords or tokens.

| Route | Event type | Target | details |
|---|---|---|---|
| POST `/api/v1/admin/accounts/students` | `ACCOUNT_CREATED` | new account | `{role}` |
| PUT `/api/v1/admin/accounts/{accountId}` | `ACCOUNT_UPDATED` | account | `{fields: [changed field names]}` |
| DELETE `/api/v1/admin/accounts/{accountId}` (`mode=soft`, default) | `ACCOUNT_DELETED` | account | `{soft: true}` |
| POST `/api/v1/admin/accounts/{accountId}/purge` (`{confirm}`) | `ACCOUNT_PURGED` | pseudonym (`target_pseudonym`) | `{trigger: ADMIN_HARD_DELETE, enrollments, refreshTokens, loginAttempts, securityEvents}` |
| POST `/api/v1/admin/accounts/{accountId}/restore` | `ACCOUNT_RESTORED` | account | `{from: DEACTIVATED|PENDING_DELETION, revokedRefreshTokens}` |
| POST `/api/v1/admin/accounts/{accountId}/unlock-login` | `ACCOUNT_LOGIN_UNLOCKED` | account | `{clearedFailures}` |
| DELETE `/api/v1/me` (actor = owner) | `ACCOUNT_DELETION_REQUESTED` | own account | `{graceDays, revokedRefreshTokens}` |
| POST `/api/v1/me/restore` (actor = owner) | `ACCOUNT_RESTORED` | own account | `{from: PENDING_DELETION, revokedRefreshTokens}` |
| GET `/api/v1/me/export` (actor = owner) | `DATA_EXPORTED` | own account | `{enrollments, securityEvents}` (counts) |
| `AccountPurgeJob` after the grace period (no actor) | `ACCOUNT_PURGED` | pseudonym | `{trigger: GRACE_EXPIRED, enrollments, refreshTokens, loginAttempts, securityEvents}` |
| PUT `/api/v1/admin/accounts/{accountId}/role` | `ROLE_CHANGED` | account | `{from, to}` |
| POST/DELETE `/api/v1/admin/accounts/{accountId}/enrollments...` (any target, the ADMIN's own account included) | `ENROLLMENT_CHANGED` | account | `{action, courseId}` |
| POST/DELETE `/api/v1/me/enrollments...` | none (self-service, not an admin mutation) | — | — |
| POST/PUT/DELETE `/api/v1/admin/courses...` | `COURSE_CHANGED` | — | `{action, courseId}` |
| POST/DELETE `/api/v1/admin/ip-allocations...` | `IP_ALLOCATION_CHANGED` | — | `{action, ipAllocationId, type}` |
| POST/PUT/DELETE `/api/v1/admin/ip-rules...` | `IP_RULE_CHANGED` | — | `{action, ipRuleId, kind, source}` |
| automatic deny rule after repeated failed logins (no route; client IP in `ip`) | `IP_RULE_CHANGED` | — | `{action: AUTO_CREATED, ipRuleId, kind: STATIC, source: AUTO}` |
| automatic login denial of an IPv6 /64 (no route; the network, e.g. `2001:0db8:0001:0002::/64`, in `ip`; in memory, no rule row) | `IP_RULE_CHANGED` | — | `{action: AUTO_CREATED|AUTO_EXTENDED, kind: IPV6_NETWORK, source: AUTO}` |
| DELETE `/api/v1/admin/job-logs` | `JOB_LOGS_DELETED` | — | `{ids}` |
| POST `/api/v1/admin/imports` | `IMPORT_UPLOADED` | — | `{kind, rows, size}` |
| POST/PUT/DELETE `/api/v1/admin/webhooks...` | `WEBHOOK_CHANGED` | — | `{action, webhookId}` |
| POST `/api/v1/admin/webhooks/{webhookId}/test` | `WEBHOOK_TEST_REQUESTED` | — | `{action: TEST, webhookId}` |

No-op requests (role already equal, account already deleted, enrollment not present) write no event.

Atomicity: the event is written in the same database transaction as the change it records. If the audit
insert fails, the whole request rolls back (the change is not applied, the client gets a 5xx); a change is
never committed without its event, and an event is never committed for a change that rolled back.
`AuditEventIT.auditWriteFailureRollsBackTheChange` injects an insert failure to prove it.

Concurrency: admin mutations load the target account with `SELECT ... FOR UPDATE`, `Account.version`
(`@Version`, `V20__account_version.sql`) rejects any write based on a stale read (409
`request/concurrent-modification`), and `PUT /api/v1/me` writes only the two name columns, so a concurrent
self-service edit can never undo a demotion or soft delete.

## Public surface (P9)

Rows 40–44 are anonymous by design and read-only (`GET` and `HEAD`). They expose published courses (`name`, `slug`, `term`,
`instructor`, `description`, `updatedAt`) and organisation facts only, as record DTOs filled by JPQL
constructor projections; no id, account, enrollment, role or flag field exists in any public response
(`src/test/java/com/educore/publicapi/PublicApiLeakIT.java` proves it by reflection, by an ArchUnit
dependency rule and by the serialised responses of a populated dataset). Every `/api/**` response carries
`X-Robots-Tag: noindex, nofollow`; `/sitemap.xml` does not.
