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
token is never trusted. A soft-deleted account is anonymous.

Cell values: the HTTP status of a valid request. `401` = not authenticated, `403` = authenticated but not
allowed, `NA` = the route cannot address another account (the account is always the caller).

## Enforcement layers

1. URL rules in `SecurityConfig` (application port):
   `POST /api/v1/auth/login|refresh|logout` and `/api/v1/public/**` are anonymous;
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
(`AuthorizationMatrixIT` fails when they differ, when a mapped `/api/**` handler has no row, or when a row has
no handler). `NA` = the route cannot address another account, `OPEN` = permitted by the URL rule.

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
| 17 | DELETE | `/api/v1/admin/accounts/{accountId}` | 204 | 403 | 403 | 401 |  |
| 18 | PUT | `/api/v1/admin/accounts/{accountId}/role` | 200 | 403 | 403 | 401 |  |
| 19 | GET | `/api/v1/admin/accounts/{accountId}/enrollments` | 200 | 403 | 403 | 401 |  |
| 20 | POST | `/api/v1/admin/accounts/{accountId}/enrollments` | 201 | 403 | 403 | 401 |  |
| 21 | DELETE | `/api/v1/admin/accounts/{accountId}/enrollments/{courseId}` | 204 | 403 | 403 | 401 |  |
| 22 | POST | `/api/v1/admin/courses` | 201 | 403 | 403 | 401 |  |
| 23 | PUT | `/api/v1/admin/courses/{courseId}` | 200 | 403 | 403 | 401 |  |
| 24 | DELETE | `/api/v1/admin/courses/{courseId}` | 204 | 403 | 403 | 401 |  |
| 25 | GET | `/api/v1/admin/ip-rules` | 200 | 403 | 403 | 401 |  |
| 26 | POST | `/api/v1/admin/ip-rules` | 201 | 403 | 403 | 401 |  |
| 27 | DELETE | `/api/v1/admin/ip-rules/{ipRuleId}` | 204 | 403 | 403 | 401 |  |
| 28 | GET | `/api/v1/admin/job-logs` | 200 | 403 | 403 | 401 |  |
| 29 | DELETE | `/api/v1/admin/job-logs` | 204 | 403 | 403 | 401 | query `ids=1,2,3` |
| 30 | GET | `/api/v1/admin/security-events` | 200 | 403 | 403 | 401 |  |
| 31 | ANY | `/api/v1/public/**` | OPEN | OPEN | OPEN | OPEN | URL rule only; no endpoint yet (P9) |
| 32 | GET | `/api/v1/accounts/students` | 404 | 404 | 404 | 401 | sample of every removed pre-P3 route and any other unmapped path |

Notes:
- Rows 8–10 operate on the caller's own account only; an `accountId` sent in the body is ignored. Rows
  19–21 are the ADMIN equivalents for any account. Both paths go through the same `EnrollmentService`
  methods, which enforce `#accountId == principal.id or hasRole('ADMIN')` a second time.
- Row 18 additionally answers 409 when the target is the caller (`account/self-role-change`) or when the
  change would leave no active ADMIN (`account/last-admin`). Row 17 answers 409 for the caller's own account
  (`account/self-delete`) and for the last active ADMIN (`account/last-admin`).
- USER-self on rows 16–21 is the privilege-escalation case (a USER addressing their own account through an
  admin route); it is denied like USER-other.

## Matrix — management port (`management.server.port`, not published by docker-compose)

| # | Method | Route | ADMIN | USER | anonymous | Test |
|---|---|---|---|---|---|---|
| M1 | GET | `/actuator/health` | 200 | 200 | 200 | `ManagementEndpointSecurityIT` |
| M2 | GET | `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus` | 200 | 403 | 401 | `ManagementEndpointSecurityIT` |

## Capabilities from the program baseline scheduled for later phases

These capabilities have no endpoint yet; their rows are added here when the endpoint is built.

| Capability | ADMIN | USER-self | USER-other | anonymous | Phase |
|---|---|---|---|---|---|
| Webhook CRUD | allow | deny | deny | deny | P6 |
| Export own data | allow (own) | allow | deny | deny | P7 |
| Request own deletion | allow (own) | allow | deny | deny | P7 |
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
| DELETE `/api/v1/admin/accounts/{accountId}` | `ACCOUNT_DELETED` | account | `{soft: true}` |
| PUT `/api/v1/admin/accounts/{accountId}/role` | `ROLE_CHANGED` | account | `{from, to}` |
| POST/DELETE `/api/v1/admin/accounts/{accountId}/enrollments...` (any target, the ADMIN's own account included) | `ENROLLMENT_CHANGED` | account | `{action, courseId}` |
| POST/DELETE `/api/v1/me/enrollments...` | none (self-service, not an admin mutation) | — | — |
| POST/PUT/DELETE `/api/v1/admin/courses...` | `COURSE_CHANGED` | — | `{action, courseId}` |
| POST/DELETE `/api/v1/admin/ip-rules...` | `IP_RULE_CHANGED` | — | `{action, ipRuleId, type}` |
| DELETE `/api/v1/admin/job-logs` | `JOB_LOGS_DELETED` | — | `{ids}` |

No-op requests (role already equal, account already deleted, enrollment not present) write no event.

Atomicity: the event is written in the same database transaction as the change it records. If the audit
insert fails, the whole request rolls back (the change is not applied, the client gets a 5xx); a change is
never committed without its event, and an event is never committed for a change that rolled back.
`AuditEventIT.auditWriteFailureRollsBackTheChange` injects an insert failure to prove it.

Concurrency: admin mutations load the target account with `SELECT ... FOR UPDATE`, `Account.version`
(`@Version`, `V20__account_version.sql`) rejects any write based on a stale read (409
`request/concurrent-modification`), and `PUT /api/v1/me` writes only the two name columns, so a concurrent
self-service edit can never undo a demotion or soft delete.
