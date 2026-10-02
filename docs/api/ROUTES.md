# API Routes (contract since P3)

Base URL in development: `http://localhost:8081`. Every route except the session endpoints needs
`Authorization: Bearer <accessToken>`. Roles per route are defined in
[`docs/security/RBAC_MATRIX.md`](../security/RBAC_MATRIX.md). Request bodies are JSON
(`Content-Type: application/json`); unknown fields are ignored, so server-owned fields (`id`, `role`,
`deleted`, `password`, `mustChangePassword`, `startIp`, `endIp`, `accountId`) can never be set through a body
unless a row below lists them.

## Security fixes (fix1) — for the frontend

| Before | Now | What the frontend must change |
|---|---|---|
| `POST /api/v1/me/restore` (no body) → 200 `Profile` | `POST /api/v1/me/restore` `{currentPassword}` → 200 `{accessToken, expiresIn, user}` + `Set-Cookie educore_rt` (exactly like login) | Ask for the current password on the "deletion scheduled" screen; send it in the body; on 200 replace the stored access token with `accessToken` (the old one is dead: every earlier session of the account ended) and continue as after a login (`user.status` is `ACTIVE`). New errors: 400 `request/invalid` (missing password), 400 `auth/invalid-current-password` (counts towards the lockout), 423 `auth/account-locked`, 429 `auth/too-many-attempts` (both with `Retry-After`). |
| `DELETE /api/v1/me` → 202; the current access token kept the restore-only scope for up to 15 min | `DELETE /api/v1/me` → 202 (unchanged body); every access token of the account stops working at once (401) | After 202 clear the stored access token and go to the login page with a "deletion scheduled" notice; signing in during the grace period gives the restore-only session. |
| `DELETE /api/v1/admin/accounts/{id}?mode=hard&confirm=<username>` | `POST /api/v1/admin/accounts/{id}/purge` with JSON body `{"confirm": "<username>"}` → 204 | Use the new route; never put the username in the URL. `DELETE ...?mode=hard` (any `mode` other than `soft`) is now 400 `request/invalid`. `DELETE /api/v1/admin/accounts/{id}` (optionally `?mode=soft`) stays the soft delete. |
| — | `POST /api/v1/admin/accounts/{id}/unlock-login` → 204 | Optional ADMIN action "unlock sign-in" on the account screens (clears the account's failed logins). |
| `mustChangePassword` enforced only by the UI | Server-side scope: a session with `user.mustChangePassword = true` may only call `GET /api/v1/auth/me`, `POST /api/v1/auth/password`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`; everything else (also `GET /api/v1/me`) is 403 `account/password-change-required` | Route such a session straight to the password-change screen without loading other data; treat any 403 `account/password-change-required` the same way. After `POST /api/v1/auth/password` use the returned session. |
| Login lock per username (423 for everybody) | 423 only for the (username, network) pair that failed 5 times; other networks may get 429 `auth/too-many-attempts` with a short `Retry-After` (1–30 s) while an account is under attack | Show 429 `auth/too-many-attempts` as "wait N seconds" using `Retry-After` (no lock message). |
| Admin soft delete / admin restore kept sessions | Both end every session of the account (refresh cookie and access tokens) | None (the owner signs in again after an admin restore). |

## P5 changes (perimeter, D-09) — for the frontend

| Before P5 | Since P5 | Change |
|---|---|---|
| `GET /api/v1/admin/ip-rules` → `IpRule[]` | `GET /api/v1/admin/ip-allocations` → `IpAllocation[]` | Renamed route; same item fields (`id`, `type`, `originalValue`), same order (by id). |
| `POST /api/v1/admin/ip-rules` `{type, originalValue}` | `POST /api/v1/admin/ip-allocations` `{type, originalValue}` | Renamed route; same body; 201 `IpAllocation`. Error codes `ip-rule/invalid` → `ip-allocation/invalid`; a CIDR block with host bits set (`10.0.0.5/8`) is now 400 `ip-allocation/invalid` (it was silently masked). |
| `DELETE /api/v1/admin/ip-rules/{ipRuleId}` | `DELETE /api/v1/admin/ip-allocations/{ipAllocationId}` | Renamed route; 204; unknown id 404 `ip-allocation/not-found` (was `ip-rule/not-found`). |
| — | `GET /api/v1/admin/ip-rules?page&size` | **New meaning**: request-level deny rules, `PageResponse<IpDenyRule>`, newest first. |
| — | `POST /api/v1/admin/ip-rules` `{kind, value, reason?, expiresAt?}` | New: 201 `IpDenyRule`; 400 `ip-rule/invalid`, `ip-rule/ipv6-unsupported`, `request/invalid`; 409 `ip-rule/self-deny`, `ip-rule/trusted-proxy`. |
| — | `GET`/`PUT`/`DELETE /api/v1/admin/ip-rules/{ipRuleId}` | New: 200 `IpDenyRule` / 200 `IpDenyRule` (same body and checks as POST) / 204; 404 `ip-rule/not-found`. |
| any route | any route | New answers: 403 `ipaccess/denied` (client IP denied by a MANUAL rule; an automatic rule after repeated failed logins refuses only `POST /api/v1/auth/login`), 429 `rate-limit/exceeded` with `Retry-After` (rate limit), 403 `request/https-required` (prod, plain HTTP), 400 `ipaccess/invalid-client-address`, 403 `ipaccess/ipv6-unsupported` (only with `ipv6-policy=DENY`), 503 `ipaccess/unavailable`. |
| any cross-origin call | any cross-origin call | CORS: `Access-Control-Allow-Credentials` only under `/api/v1/auth/**`; send cookies (`withCredentials`) only on auth calls. Allowed request headers are now an explicit list (`Authorization`, `Content-Type`, `Accept`, `Accept-Language`, `If-None-Match`, `X-Request-Id`). |

The audit event of the allocation routes is `IP_ALLOCATION_CHANGED` `{action, ipAllocationId, type}` (was
`IP_RULE_CHANGED` `{action, ipRuleId, type}`); `IP_RULE_CHANGED` now records deny rule changes. Background:
[`docs/security/IP_ACCESS.md`](../security/IP_ACCESS.md), headers and CORS:
[`docs/security/HEADERS.md`](../security/HEADERS.md).

## Old route → new route (P3)

Every pre-P3 route below is removed (an authenticated call answers 404 or 405, an anonymous call 401).

| Old route | New route | Method | Role | Change in shape |
|---|---|---|---|---|
| `POST /api/v1/enroll` `{accountId, courseId}` | `POST /api/v1/me/enrollments` `{courseId}` | POST | any authenticated (self) | Account is the caller; 201 + `Course` (was 200 + message string). |
| `POST /api/v1/enroll` `{accountId, courseId}` (ADMIN for a student) | `POST /api/v1/admin/accounts/{accountId}/enrollments` `{courseId}` | POST | ADMIN | Account from the path; 201 + `Course`. |
| `GET /api/v1/accounts/{accountId}/courses` (own) | `GET /api/v1/me/enrollments` | GET | any authenticated (self) | Same item shape (`Course[]`). |
| `GET /api/v1/accounts/{accountId}/courses` (ADMIN for a student) | `GET /api/v1/admin/accounts/{accountId}/enrollments` | GET | ADMIN | Same item shape (`Course[]`). |
| `DELETE /api/v1/accounts/{accountId}/courses/{courseId}` (own) | `DELETE /api/v1/me/enrollments/{courseId}` | DELETE | any authenticated (self) | 204, no body (was 200 + message). |
| `DELETE /api/v1/accounts/{accountId}/courses/{courseId}` (ADMIN) | `DELETE /api/v1/admin/accounts/{accountId}/enrollments/{courseId}` | DELETE | ADMIN | 204, no body. |
| `GET /api/v1/accounts/students?search&page&size&sortBy&direction&isDeleted=0\|1` | `GET /api/v1/admin/accounts/students?search&page&size&direction&deleted=false\|true` | GET | ADMIN (was any authenticated) | `PageResponse<Account>`; `sortBy` removed (always first name, then id); `isDeleted` → `deleted` boolean; items lose `deleted`, gain `username`. |
| `GET /api/v1/accounts?search&page&size` | `GET /api/v1/admin/accounts?search&page&size&deleted=false\|true` | GET | ADMIN (was any authenticated) | `PageResponse<Account>`; lists active accounts by default (`deleted=true` for soft-deleted ones); search also matches the username. |
| `POST /api/v1/accounts/student` | `POST /api/v1/admin/accounts/students` | POST | ADMIN | 201 + `CreatedStudent` (was 200); `deleted`/`mustChangePassword` no longer in the response; duplicate student number 409 (was 400). |
| `PUT /api/v1/accounts/{id}` | `PUT /api/v1/admin/accounts/{accountId}` | PUT | ADMIN (was any authenticated) | Body `UpdateStudentRequest`; 200 + `Account` (was 200 + message). |
| `DELETE /api/v1/accounts/{id}` | `DELETE /api/v1/admin/accounts/{accountId}` | DELETE | ADMIN (was any authenticated) | 204, no body; 409 for own account / last ADMIN. |
| `PUT /api/v1/accounts/{id}/role` `{role}` | `PUT /api/v1/admin/accounts/{accountId}/role` `{role}` | PUT | ADMIN (was any authenticated) | 200 + `Account`; 400 for an unknown role; 409 for own role / last ADMIN. |
| `GET /api/v1/courses` | `GET /api/v1/courses` | GET | any authenticated | Unchanged path and shape; now sorted by name. |
| `POST /api/v1/courses` | `POST /api/v1/admin/courses` | POST | ADMIN (was any authenticated) | 201 + `Course` (was 200). |
| `PUT /api/v1/courses/{id}` | `PUT /api/v1/admin/courses/{courseId}` | PUT | ADMIN (was any authenticated) | 200 + `Course` (was 200 + message). |
| `DELETE /api/v1/courses/{id}` | `DELETE /api/v1/admin/courses/{courseId}` | DELETE | ADMIN (was any authenticated) | 204, no body. |
| `GET /api/v1/ip-blocks` and `GET /api/v1/ips` | `GET /api/v1/admin/ip-allocations` (P3: `/admin/ip-rules`) | GET | ADMIN (was any authenticated) | `IpAllocation[]`; `startIp`/`endIp` no longer exposed. |
| `POST /api/v1/ip-blocks` `{type, originalValue}` | `POST /api/v1/admin/ip-allocations` `{type, originalValue}` (P3: `/admin/ip-rules`) | POST | ADMIN (was any authenticated) | 201 + `IpAllocation` (was 200 + entity); errors are problems (was `{"error"}`). |
| `DELETE /api/v1/ip-blocks/{id}` | `DELETE /api/v1/admin/ip-allocations/{ipAllocationId}` (P3: `/admin/ip-rules/{ipRuleId}`) | DELETE | ADMIN (was any authenticated) | 204, no body; 404 for an unknown id. |
| `GET /api/v1/logs` | `GET /api/v1/admin/job-logs` | GET | ADMIN (was any authenticated) | `JobLog[]` (same fields as before). |
| `DELETE /api/v1/logs?ids=1,2` | `DELETE /api/v1/admin/job-logs?ids=1,2` | DELETE | ADMIN (was any authenticated) | 204, no body; 400 for a malformed list. |
| `GET /api/weather` | `GET /api/v1/weather` | GET | any authenticated (was anonymous) | Same body. |
| `/ws/**` (SOAP) | — | — | — | Removed in P2 (D-02). |
| — (new) | `GET /api/v1/me` | GET | any authenticated (self) | `Profile`. |
| — (new) | `PUT /api/v1/me` `{firstName, lastName}` | PUT | any authenticated (self) | `Profile`. |
| — (new) | `GET /api/v1/admin/security-events?page&size` | GET | ADMIN | `PageResponse<SecurityEvent>`, newest first. |

Unchanged: `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout` (anonymous),
`GET /api/v1/auth/me`, `POST /api/v1/auth/password` (any authenticated). Their shapes are listed in the complete
table below and did not change in P3.

## Complete route table

| Method | Route | Role | Request | Success response |
|---|---|---|---|---|
| POST | `/api/v1/auth/login` | anonymous | `{username, password}` | 200 `{accessToken, expiresIn, user: UserView}` + refresh cookie |
| POST | `/api/v1/auth/refresh` | anonymous (cookie, allowed Origin) | — | 200 same as login |
| POST | `/api/v1/auth/logout` | anonymous (allowed Origin) | — | 204 |
| GET | `/api/v1/auth/me` | authenticated | — | 200 `UserView` |
| POST | `/api/v1/auth/password` | authenticated | `{currentPassword, newPassword}` | 200 same as login |
| GET | `/api/v1/me` | authenticated | — | 200 `Profile` |
| PUT | `/api/v1/me` | authenticated | `UpdateProfileRequest` | 200 `Profile` |
| GET | `/api/v1/me/enrollments` | authenticated | — | 200 `Course[]` |
| POST | `/api/v1/me/enrollments` | authenticated | `EnrollRequest` | 201 `Course` |
| DELETE | `/api/v1/me/enrollments/{courseId}` | authenticated | — | 204 |
| GET | `/api/v1/courses` | authenticated | — | 200 `Course[]` |
| GET | `/api/v1/weather` | authenticated | — | 200 `CityWeather[]` |
| GET | `/api/v1/admin/accounts` | ADMIN | query `search=""`, `deleted=false` (`false` = `ACTIVE`; `true` = `DEACTIVATED` or `PENDING_DELETION`, since P7), `page=0`, `size=10`, `sort=id`, `direction=asc` | 200 `PageResponse<Account>` |
| GET | `/api/v1/admin/accounts/students` | ADMIN | query `search=""`, `deleted=false` (as above), `page=0`, `size=10`, `sort=firstName`, `direction=asc` | 200 `PageResponse<Account>` (role USER only) |
| POST | `/api/v1/admin/accounts/students` | ADMIN | `CreateStudentRequest` | 201 `CreatedStudent` |
| PUT | `/api/v1/admin/accounts/{accountId}` | ADMIN | `UpdateStudentRequest` | 200 `Account` |
| DELETE | `/api/v1/admin/accounts/{accountId}` | ADMIN | query `mode=soft` (default; the only accepted value since fix1) | 204 (`DEACTIVATED`, every session of the account ended; repeat or a non-active account is a no-op) |
| POST | `/api/v1/admin/accounts/{accountId}/purge` | ADMIN | `PurgeAccountRequest` `{confirm}` (since fix1) | 204 (purged at once) |
| POST | `/api/v1/admin/accounts/{accountId}/unlock-login` | ADMIN | — (since fix1) | 204 (failed logins of the account cleared) |
| PUT | `/api/v1/admin/accounts/{accountId}/role` | ADMIN | `ChangeRoleRequest` | 200 `Account` |
| GET | `/api/v1/admin/accounts/{accountId}/enrollments` | ADMIN | — | 200 `Course[]` |
| POST | `/api/v1/admin/accounts/{accountId}/enrollments` | ADMIN | `EnrollRequest` | 201 `Course` |
| DELETE | `/api/v1/admin/accounts/{accountId}/enrollments/{courseId}` | ADMIN | — | 204 (no-op if not enrolled) |
| POST | `/api/v1/admin/courses` | ADMIN | `CourseRequest` | 201 `Course` |
| PUT | `/api/v1/admin/courses/{courseId}` | ADMIN | `CourseRequest` | 200 `Course` |
| DELETE | `/api/v1/admin/courses/{courseId}` | ADMIN | — | 204 |
| GET | `/api/v1/admin/ip-allocations` | ADMIN | — | 200 `IpAllocation[]` (by id) |
| POST | `/api/v1/admin/ip-allocations` | ADMIN | `IpAllocationRequest` | 201 `IpAllocation` |
| DELETE | `/api/v1/admin/ip-allocations/{ipAllocationId}` | ADMIN | — | 204 |
| GET | `/api/v1/admin/ip-rules` | ADMIN | query `page=0`, `size=20` (max 100) | 200 `PageResponse<IpDenyRule>` (newest first) |
| POST | `/api/v1/admin/ip-rules` | ADMIN | `IpDenyRuleRequest` | 201 `IpDenyRule` |
| GET | `/api/v1/admin/ip-rules/{ipRuleId}` | ADMIN | — | 200 `IpDenyRule` |
| PUT | `/api/v1/admin/ip-rules/{ipRuleId}` | ADMIN | `IpDenyRuleRequest` | 200 `IpDenyRule` (`source`, `createdBy`, `createdAt` kept) |
| DELETE | `/api/v1/admin/ip-rules/{ipRuleId}` | ADMIN | — | 204 |
| GET | `/api/v1/admin/job-logs` | ADMIN | query `status`, `from`, `to`, `file`, `page`, `size`, `sort`, `direction` (since P6) | 200 `Page<JobLog>` (newest first) |
| DELETE | `/api/v1/admin/job-logs` | ADMIN | query `ids=1,2,3` (1–500 positive ids; unknown ids skipped) | 204 |
| GET | `/api/v1/admin/security-events` | ADMIN | query `page=0`, `size=20` (max 100) | 200 `PageResponse<SecurityEvent>` |

Paging, sorting and search parameters (since P4):

- `page` is zero-based and must be ≥ 0; `size` must be 1–100. Values outside these bounds are rejected with
  400 `request/invalid` (`errors` names `page` or `size` with code `range`); they are never silently capped.
  Defaults: `page=0`; `size=10` on the account listings and `size=20` on `/admin/security-events`.
- `sort` takes one key from the route's whitelist; `direction` is `asc` or `desc` (any case, default `asc`).
  An unknown key or direction is 400 `sort/invalid`. Ties are always broken by `id` ascending.

  | Route | `sort` keys | Default |
  |---|---|---|
  | `GET /api/v1/admin/accounts` | `id`, `username`, `firstName`, `lastName`, `studentNumber` | `id` |
  | `GET /api/v1/admin/accounts/students` | `firstName`, `lastName`, `studentNumber`, `id` | `firstName` |

  Every other listing has a fixed order and no `sort` parameter: `GET /courses` by name,
  `GET /admin/ip-allocations` by id, `GET /admin/ip-rules`, `GET /admin/job-logs` and
  `GET /admin/security-events` newest first.
- `search` is at most 100 characters without control characters. It matches literally and case-insensitively
  inside first name, last name and student number (and username on `/admin/accounts`); `%` and `_` are
  ordinary characters, not wildcards.
- Path ids (`accountId`, `courseId`, `ipAllocationId`, `ipRuleId`) must be positive integers; `0`, negative or non-numeric
  ids are 400 `request/invalid`.
- The row offset `page × size` must not exceed 2147483647; a larger `page` is 400 (`page:range`).

## Shapes

Requests (Bean Validation since P4; an empty string in an optional field means "none"). JSON binding is strict:
a value must already have the declared JSON type. Numbers for enums (`{"role": 0}`), fractions for integers
(`1.9`), strings for numbers or booleans (`"5"`), numbers or booleans for strings, `null` for primitives and
anything after the first JSON value are 400 `request/invalid` (`code` `type` or `enum`). Unknown properties
are still ignored. Text fields described as "single-line" reject every Unicode control, format and
line/paragraph separator character (categories Cc, Cf, Zl, Zp, Cs; e.g. U+0085, U+200B, U+202E, U+2028).

| Name | Fields |
|---|---|
| `UpdateProfileRequest` | `firstName` (required, ≤ 100, name), `lastName` (required, ≤ 100, name) |
| `EnrollRequest` | `courseId` (required, positive integer) |
| `CreateStudentRequest` | `firstName` (required, ≤ 100, name), `lastName` (≤ 100, name), `studentNumber` (optional, `^[0-9]{4,12}$`), `username` (optional, 3–100 letters, digits, `.`, `_`, `-`; generated from the first name when absent), `ipAddress` (optional dotted-quad IPv4 inside an IP allocation range) |
| `UpdateStudentRequest` | `firstName` (required, ≤ 100, name), `lastName` (≤ 100, name), `studentNumber` (`^[0-9]{4,12}$`; empty removes it), `ipAddress` (dotted-quad IPv4 inside an IP allocation range; empty or absent removes it). All four are replaced. |
| `ChangeRoleRequest` | `role`: `"ADMIN"` or `"USER"` (required) |
| `LoginRequest` | `username` (required, ≤ 255, no control characters), `password` (required, ≤ 128) |
| `PasswordChangeRequest` | `currentPassword` (required, ≤ 128), `newPassword` (required; policy violations are listed in `violations`) |
| `CourseRequest` | `name` (required, ≤ 150, unique), `term` (≤ 50), `instructor` (≤ 100); single-line text without control characters |
| `IpAllocationRequest` | `type`: `"STATIC"`, `"RANGE"` or `"CIDR"` (required); `originalValue` (required): `192.168.1.10`, `192.168.1.1-192.168.1.10` or `192.168.1.0/24` (prefix 0–32, no leading zeros, network address) |
| `IpDenyRuleRequest` | `kind`: `"STATIC"`, `"RANGE"` or `"CIDR"` (required); `value` (required, ≤ 43, hex digits, `.`, `:`, `/`, `-` only): `203.0.113.7`, `203.0.113.10-203.0.113.20` or `203.0.113.0/24` (IPv4 only; network address); `reason` (optional, ≤ 200, single line); `expiresAt` (optional ISO-8601 instant in the future; absent = permanent) |

Responses:

| Name | Fields |
|---|---|
| `UserView` | `id`, `firstName`, `role`, `mustChangePassword`, `status` (`ACTIVE` or `PENDING_DELETION`, since P7) (auth endpoints only) |
| `Profile` | `id`, `username`, `firstName`, `lastName`, `studentNumber`, `role`, `status` (`ACTIVE` or `PENDING_DELETION`), `deleteAfter` (ISO instant or null) (status fields since P7) |
| `Account` | `id`, `username`, `firstName`, `lastName`, `studentNumber`, `role`, `ipAddress`, `status` (`ACTIVE`, `DEACTIVATED`, `PENDING_DELETION`), `deleteAfter` (ISO instant or null) (status fields since P7; `CreatedStudent` does not carry them) |
| `CreatedStudent` | `Account` fields + `temporaryPassword` (24 chars, shown once to the ADMIN; the student must change it at first login) |
| `Course` | `id`, `name`, `term`, `instructor` |
| `IpAllocation` | `id`, `type`, `originalValue` |
| `IpDenyRule` | `id`, `kind`, `value` (canonical), `startIp`, `endIp` (dotted quads), `reason`, `source` (`MANUAL`, `AUTO`), `expiresAt` (ISO instant or null), `createdBy` (account id or null), `createdAt` |
| `JobLog` | `id`, `fileName`, `entityType`, `status` (`SUCCEEDED`, `PARTIAL`, `FAILED`; `null` while running), `reason`, `readRecords`, `successfulRecords` (written), `failedRecords` (skipped), `importedFileId`, `createdAt` (ISO local date-time), `startedAt`, `finishedAt` (since P6; `detailedLogs` was replaced by `GET /admin/job-logs/{id}/entries`) |
| `SecurityEvent` | `id`, `type`, `actorAccountId`, `targetAccountId`, `actorPseudonym`, `targetPseudonym` (`purged:<16 hex>` replacing the id of a purged account, else null; since P7), `ip`, `requestId`, `at` (ISO instant), `details` (object or null) |
| `CityWeather` | `city`, `temperature`, `windSpeed`, `weatherCode`, `description`, `status` (`OK`, `STALE`, `UNAVAILABLE`, since P6), `observedAt` |
| `PageResponse<T>` | `content` (`T[]`), `page`, `size`, `totalElements`, `totalPages` |

"name" = starts with a letter; then letters, combining marks, spaces, `'`, `.` and `-` (e.g. `Ayşe Nur`,
`O'Neil-Öztürk`). Every response is JSON (`application/json` or `application/problem+json`) with
`X-Content-Type-Options: nosniff`; no route renders HTML, whatever the `Accept` header asks for.

No response contains a password hash, `deleted`, `mustChangePassword` (except `UserView`) or nested entities.

## Errors

Every error, on every route and also for requests that never reach a controller (unknown path, firewall
rejection, payload too large, missing or insufficient credentials), is an RFC 9457 problem with
`Content-Type: application/problem+json` and `X-Content-Type-Options: nosniff`:

```json
{
  "type": "/problems/request/invalid",
  "title": "The request is invalid.",
  "status": 400,
  "detail": "One or more request values are missing or invalid.",
  "instance": "/api/v1/admin/courses",
  "code": "request/invalid",
  "errors": [{"field": "name", "code": "size"}]
}
```

- `type` is `<educore.problems.base-url>/<code>` (`/problems/...` by default); `code` repeats the stable code.
- `title` and `detail` are fixed English sentences per code or status; they never contain exception
  messages, SQL, class names or request values. `instance` is the request path.
- `errors` (400 validation only): `field` is the JSON property path, query parameter or path variable
  (`body` for the body as a whole); `code` is one of `required`, `size`, `pattern`, `range`, `type`, `enum`,
  `malformed`, `invalid`. Rejected values are never echoed (passwords and tokens included).
- `correlationId` (5xx only): the request's `X-Request-Id`; the server logs the failure under it.
- `violations` (`auth/password-policy` only): password policy rule names.
- There is no `message`, `error`, `trace`, `exception`, `timestamp` or `path` member.

| Status | Code | When |
|---|---|---|
| 400 | `request/invalid` | Malformed JSON, missing/invalid field, unknown enum value, bad query or path parameter (`errors` lists them) |
| 400 | `auth/invalid-request` | Same as `request/invalid` on the `/api/v1/auth/*` routes |
| 400 | `sort/invalid` | `sort` key not in the route's whitelist, or `direction` not `asc`/`desc` |
| 400 | `account/ip-address-not-allocatable` | `ipAddress` is outside every IP allocation range |
| 400 | `account/ip-address-invalid` | Service-side re-check of the IPv4 format; a malformed `ipAddress` is normally answered first by `request/invalid` (`ipAddress:pattern`) |
| 400 | `ip-allocation/invalid` | `originalValue` is well-formed but does not match `type`, a range ends before it starts, or a CIDR block has host bits set |
| 400 | `ip-rule/invalid` | Deny rule `value` is not a valid IPv4 address, range or CIDR block of its `kind` |
| 400 | `ip-rule/ipv6-unsupported` | Deny rule `value` is IPv6 |
| 400 | `auth/invalid-current-password`, `auth/password-policy` | Password change (P2) |
| 401 | `auth/unauthenticated` | Missing, invalid or expired access token (refresh, then retry once) |
| 401 | `auth/invalid-credentials`, `auth/invalid-refresh-token` | Login / refresh (P2) |
| 403 | `auth/access-denied` | Authenticated but the role does not allow the route |
| 403 | `account/password-change-required` | The account must change its password first (`mustChangePassword`): any request other than `GET /api/v1/auth/me`, `POST /api/v1/auth/password`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`, on the application and the management port (since fix1) |
| 403 | `auth/origin-rejected` | Refresh/logout from an origin outside the allow-list |
| 400 | `ipaccess/invalid-client-address` | A trusted proxy forwarded a client address that is not an IP address |
| 403 | `ipaccess/denied` | The client IP is covered by an active MANUAL deny rule (every route), or by an AUTO rule (only `POST /api/v1/auth/login`); no rule details in the body; checked before CORS and authentication |
| 403 | `ipaccess/ipv6-unsupported` | Native IPv6 client while `educore.ipaccess.ipv6-policy=DENY` (default `ALLOW`) |
| 403 | `request/https-required` | `prod`: the request did not arrive over HTTPS (directly or via `X-Forwarded-Proto: https` from a trusted proxy); never a redirect |
| 403 | `request/cors-rejected` | Cross-origin request (or preflight) from an origin, method or header outside the CORS allow-list; no `Access-Control-Allow-*` headers |
| 404 | `request/not-found` | No route at this path |
| 404 | `account/not-found`, `course/not-found`, `ip-allocation/not-found`, `ip-rule/not-found` | Unknown id; the enrollment routes also treat an account that is not `ACTIVE` as unknown |
| 405 | `request/method-not-allowed` | The path exists but not for this method (`Allow` lists the methods) |
| 406 | `request/not-acceptable` | The client accepts no JSON representation |
| 409 | `account/self-role-change` | ADMIN changes their own role |
| 409 | `account/self-delete` | ADMIN deletes their own account |
| 409 | `account/last-admin` | The change would leave no active ADMIN |
| 409 | `account/student-number-taken`, `account/username-taken`, `account/ip-address-taken` | Uniqueness |
| 409 | `enrollment/already-enrolled` | Duplicate enrollment |
| 409 | `ip-rule/self-deny` | The deny rule would cover the ADMIN's own current client IP |
| 409 | `ip-rule/trusted-proxy` | The deny rule would cover a trusted reverse proxy |
| 409 | `request/concurrent-modification` | Another request changed the same account first (optimistic lock); reload and retry |
| 409 | `request/conflict` | A database constraint, e.g. deleting a course that still has enrollments or reusing a course name |
| 413 | `request/payload-too-large` | Non-multipart body above `educore.http.max-body-size` (default 64 KB; checked on `Content-Length` and while reading chunked bodies, before authentication), or a multipart upload above `spring.servlet.multipart.max-file-size` / `max-request-size` |
| 415 | `request/unsupported-media-type` | Body is not `application/json` |
| 423 | `auth/account-locked` | 5 failed logins (or current-password checks) of this username from this client network (IPv4 address or IPv6 /64) within 15 minutes; only that pair is locked; `Retry-After` (seconds) |
| 429 | `auth/too-many-attempts` | Login rate limit per client network (10/min), or the per-account progressive delay after 5 consecutive failures from any network (1 s doubling up to 30 s; a network the owner signed in from within 30 days is exempt); `Retry-After` (seconds) |
| 429 | `rate-limit/exceeded` | Request rate limit (60/min per IP anonymous, 300/min per account authenticated, 120/min per IP on `/api/v1/public/**`); `Retry-After` (seconds) |
| 4xx | `request/rejected` | Any other client error |
| 503 | `ipaccess/unavailable` | The IP deny rules could not be loaded since startup (fail closed); retry later |
| 5xx | `server/internal-error` | Unexpected failure; only `correlationId` identifies it |

## Public API and sitemap (since P9)

Anonymous, read-only routes for the public site (`GET` and `HEAD`; any other method needs authentication and
is then 405). They need no token (a valid token changes nothing), expose
published courses only and answer with record DTOs that contain no id, account, enrollment, role or flag
field (`PublicApiLeakIT` checks the exact serialised schema). Matrix rows 40–44 in
[`RBAC_MATRIX.md`](../security/RBAC_MATRIX.md).

| Method | Route | Request | Success response |
|---|---|---|---|
| GET | `/api/v1/public/courses` | query `page=0`, `size=20` (1–100), `sort=name` (`name`, `term`, `updatedAt`), `direction=asc` | 200 `PageResponse<PublicCourse>` |
| GET | `/api/v1/public/courses/{slug}` | — | 200 `PublicCourse`; 404 `course/not-found` for an unknown, unpublished or malformed slug |
| GET | `/api/v1/public/site-facts` | — | 200 `SiteFacts` |
| GET | `/sitemap.xml` | — | 200 `application/xml` (sitemaps.org 0.9): the only sitemap file, or a sitemap index when the URLs exceed `educore.seo.sitemap-max-urls` |
| GET | `/sitemap-courses-{n}.xml` | — | 200 `application/xml`: sitemap file `n` (1-based); 404 `sitemap/not-found` outside 1..number of files |

- Caching: every 200 carries `Cache-Control: max-age=300, public` and a strong `ETag` (SHA-256 of the
  representation). A request whose `If-None-Match` matches (weak comparison, so `W/"…"` matches too; any
  entry of a list; `*` for any existing representation) gets `304 Not Modified` with the same `ETag` and
  `Cache-Control` and no body. `HEAD` answers like `GET` without a body. Errors (and every non-public
  response) carry neither header; Spring Security's `Cache-Control: no-cache, no-store, max-age=0,
  must-revalidate` applies to them. An old validator of an unpublished course is answered with 404, never 304.
- CORS: preflights may ask for `HEAD` (`Access-Control-Allow-Methods` includes it); `If-None-Match` is an
  allowed request header and `ETag` an exposed response header.
- Robots: every `/api/**` response (public routes and errors included) carries
  `X-Robots-Tag: noindex, nofollow`; `/sitemap.xml` does not.
- Sorting follows the rules above (whitelist, `asc`/`desc`, 400 `sort/invalid`); ties are broken by `slug`.
- Sitemap: `<loc>` is `educore.seo.base-url` + `/`, `/courses`, `/about`, `/faq`, `/privacy`, `/security`
  (file 1 only) and `/courses/<slug>` for every published course, by name, `sitemap-max-urls` − 6 courses per
  file (default `educore.seo.sitemap-max-urls=45000`, allowed 10–50,000). Above one file, `/sitemap.xml` is a
  `<sitemapindex>` listing `<base-url>/sitemap-courses-<n>.xml`; no URL is dropped. `<lastmod>` (W3C
  date-time, UTC, seconds) is the course's `updatedAt`; `/`, `/courses` and the index entries carry the
  catalog revision; the content pages carry no `lastmod` (no stored modification date).
- Catalog revision (`catalog_revision`, `V42`): advanced in the same transaction by every ADMIN change that a
  visitor can see (a published course created, changed, unpublished or deleted, or a course published) and
  never moved backwards; `SiteFacts.dateModified` is the revision (never older than the newest published
  course), `null` while nothing is published.
- `educore.seo.base-url` (`EDUCORE_SEO_BASE_URL`) must be an absolute `http`/`https` origin without user
  info, path, query or fragment; the application refuses to start otherwise.

| Name | Fields |
|---|---|
| `PublicCourse` | `name`, `slug`, `term`, `instructor`, `description`, `updatedAt` (ISO instant) |
| `SiteFacts` | `name` (`educore.seo.name`, default `EduCore`), `baseUrl` (`educore.seo.base-url`, no trailing slash), `description` (`educore.seo.description`), `languages` (`educore.seo.languages`, default `["tr", "en"]`), `dateModified` (catalog revision, `null` while nothing is published) |

Course fields added for the public catalog (`V40__course_public_fields.sql`):

- `CourseRequest` (ADMIN create/update) also accepts `slug` (≤ 80, `^[a-z0-9]+(?:-[a-z0-9]+)*$`, unique),
  `description` (≤ 1000, single line; empty removes it) and `published` (boolean). Each of the three is
  optional: `null`/absent keeps the stored value, so clients that do not send them change nothing. A course
  without a slug gets the first free one derived from its name (ASCII transliteration, lowercase, hyphens,
  stem ≤ 50 characters, then `-2`, `-3`, ...). A slug used by another course is 409 `course/slug-taken`, also
  when a concurrent insert wins the unique index; a generated slug that loses such a race is generated again
  in a fresh transaction (at most 3 attempts).
- The slug of a published course is immutable: a different `slug` is 409 `course/slug-immutable`, even when
  the same request unpublishes the course. Unpublish first, then change the slug (a published URL never
  silently changes).
- Courses carry an optimistic lock (`version`, `V41`): an edit based on a stale read, for example one racing
  an unpublish, is 409 `request/concurrent-modification` and never republishes the course.
- `Course` responses (`/api/v1/courses`, enrollments, admin course routes) also contain `slug`,
  `description` and `published`.
- Existing courses were given a slug from their name (colliding stems get `-2`, `-3`, ... in id order; ids
  never appear in a slug) and start unpublished.
  `updatedAt` is set on every insert and update.

## Ingestion and webhooks (since P6)

| Method | Route | Role | Request | Response |
|---|---|---|---|---|
| GET | `/api/v1/admin/job-logs` | ADMIN | `status` = `SUCCEEDED`\|`PARTIAL`\|`FAILED`; `from`, `to` = ISO-8601 date-time with offset (start time, inclusive; `from` after `to` is 400 with `errors: [{field: from, code: range}]`); `file` = case-insensitive part of the file name (≤ 100); `sort` = `createdAt`\|`startedAt`\|`fileName`\|`status`, `direction` = `asc`\|`desc` (default `createdAt desc`; other keys 400 `sort/invalid`); `page` ≥ 0, `size` 1–100 (default 20) | 200 `Page<JobLog>` |
| GET | `/api/v1/admin/job-logs/{jobLogId}/entries` | ADMIN | `page`, `size` (default 50) | 200 `Page<JobLogEntry>` in line order; 404 `job-log/not-found` |
| POST | `/api/v1/admin/imports` | ADMIN | `multipart/form-data`, part `file` (a `.csv` file; same checks as the inbox) | 202 `ImportAccepted` (the file reaches `inbox/` when the request's transaction committed); 400 `import/invalid-file-name`, `import/empty-file`, `import/not-text`, `import/not-utf8`, `import/invalid-line-break`, `import/record-too-long`, `import/invalid-header`, `import/no-data-rows`, `import/too-many-rows`; 409 `import/duplicate`; 413 `import/file-too-large` or `request/payload-too-large` |
| GET | `/api/v1/admin/webhooks` | ADMIN | — | 200 `Webhook[]` (by id) |
| POST | `/api/v1/admin/webhooks` | ADMIN | `{url, events[], active?}` | 201 `{webhook: Webhook, secret}` (the only time the secret is shown); 400 `webhook/invalid-url` or `request/invalid`; 409 `webhook/limit-reached` (20 subscriptions) |
| GET | `/api/v1/admin/webhooks/{webhookId}` | ADMIN | — | 200 `Webhook`; 404 `webhook/not-found` |
| PUT | `/api/v1/admin/webhooks/{webhookId}` | ADMIN | `{url, events[], active?}` | 200 `Webhook` |
| DELETE | `/api/v1/admin/webhooks/{webhookId}` | ADMIN | — | 204 (deliveries are deleted too) |
| POST | `/api/v1/admin/webhooks/{webhookId}/test` | ADMIN | — | 202 `{deliveryId}` (a `webhook.test` delivery); 409 `webhook/queue-full`; 429 `webhook/too-many-test-events` (5 per ADMIN and minute, `Retry-After`) |
| GET | `/api/v1/admin/webhooks/{webhookId}/deliveries` | ADMIN | `page`, `size` | 200 `Page<WebhookDelivery>` (newest first) |

| Shape | Fields |
|---|---|
| `JobLogEntry` | `id`, `rowNumber` (physical line in the file, header = 1; `null` for a file-level entry), `level` (`INFO`, `WARN`, `ERROR`), `reason` (`DUPLICATE_IN_FILE`, `ALREADY_EXISTS`, `INVALID_FIELD:<fields>`, `WRONG_COLUMN_COUNT`, `PARSE_ERROR`, `CONSTRAINT_VIOLATION`, or a file-level code such as `INVALID_HEADER`), `rawMasked` (the CSV line with each word reduced to its first character + `***`) |
| `ImportAccepted` | `inboxFileName`, `kind` (`STUDENTS`, `COURSES`), `rows`, `size`, `sha256` |
| `Webhook` | `id`, `url`, `events` (`import.completed`, `import.failed`, `course.updated`, `account.deleted`), `active`, `createdBy`, `createdAt`, `updatedAt`, `droppedEvents` (events not queued because 1 000 deliveries were pending) (never the secret) |
| `WebhookDelivery` | `id` (= `X-EduCore-Delivery`), `event`, `attempt`, `status` (`PENDING`, `DELIVERED`, `FAILED`), `nextAttemptAt`, `responseCode`, `lastError`, `createdAt`, `deliveredAt` |

Breaking change: `GET /api/v1/admin/job-logs` answers a page (`{content, page, size, totalElements, totalPages}`)
instead of an array, and `JobLog.detailedLogs` is gone (entries endpoint). Request signing and retries:
[`docs/integrations/WEBHOOKS.md`](../integrations/WEBHOOKS.md).

`GET /api/v1/weather` (since P6): at most 30 requests per account and minute; beyond that 429
`weather/too-many-requests` with `Retry-After`. `CityWeather.status` is `STALE` or `UNAVAILABLE` when the provider fails
or answers incompletely; the list always holds every city.

## Account lifecycle and data export (since P7)

Erasure, restore and portability (`docs/ops/DATA_RETENTION.md`). Authorization rows 17, 70–74 of
`docs/security/RBAC_MATRIX.md`.

| Method | Route | Role | Request | Response |
|---|---|---|---|---|
| DELETE | `/api/v1/me` | authenticated (self, `ACTIVE`) | JSON `DeleteAccountRequest` | 202 `DeletionScheduled`, `Cache-Control: no-store`, `Set-Cookie` clearing `educore_rt` (every session of the account is revoked and every access token, the calling one included, stops working at once); 400 `request/invalid` (missing body or field), 400 `auth/invalid-current-password` (counts towards the login lockout), 423 `auth/account-locked`, 429 `auth/too-many-attempts`, 409 `account/last-admin` |
| POST | `/api/v1/me/restore` | authenticated (self; also in the restore-only scope) | JSON `RestoreAccountRequest` (since fix1) | 200 same as login (`{accessToken, expiresIn, user}` with `user.status: ACTIVE`, `Cache-Control: no-store`, new `educore_rt` cookie); every earlier refresh family and access token of the account ends. For an account that is already active nothing changes and the answer is a new session as well. 400 `request/invalid` (missing body or field), 400 `auth/invalid-current-password` (counts towards the login lockout), 423 `auth/account-locked`, 429 `auth/too-many-attempts` |
| GET | `/api/v1/me/export` | authenticated (self, `ACTIVE`) | — | 200 `AccountExport` with `Content-Type: application/json`, `Content-Disposition: attachment; filename="educore-account-export-<yyyyMMdd>.json"` (UTC date), `Cache-Control: no-store`, `Pragma: no-cache`; 429 `rate-limit/exceeded` with `Retry-After` (seconds) on the second export within a minute |
| DELETE | `/api/v1/admin/accounts/{accountId}` | ADMIN | query `mode=soft` (default and only value) | 204 soft delete (every session of the account ends); 400 `request/invalid` (`mode:pattern`, e.g. the removed `mode=hard`), 404 `account/not-found`, 409 `account/self-delete`, 409 `account/last-admin` |
| POST | `/api/v1/admin/accounts/{accountId}/purge` | ADMIN | JSON `PurgeAccountRequest` | 204, purged at once; 400 `request/invalid` (missing body or `confirm`), 400 `account/confirmation-mismatch`, 404 `account/not-found`, 409 `account/self-delete`, 409 `account/last-admin` |
| POST | `/api/v1/admin/accounts/{accountId}/restore` | ADMIN | — | 200 `Account` with `status: ACTIVE` (from `DEACTIVATED` or `PENDING_DELETION`; no-op 200 when already active); every earlier session of the account stays ended (the owner signs in again); 404 `account/not-found` |
| POST | `/api/v1/admin/accounts/{accountId}/unlock-login` | ADMIN | — | 204; every failed login attempt of the account is deleted (all lockouts and the progressive delay end), audited `ACCOUNT_LOGIN_UNLOCKED`; 404 `account/not-found` |

| Shape | Fields |
|---|---|
| `DeleteAccountRequest` | `currentPassword` (required, ≤ 128) |
| `RestoreAccountRequest` | `currentPassword` (required, ≤ 128) |
| `PurgeAccountRequest` | `confirm` (required, ≤ 255, must equal the account's `username`) |
| `DeletionScheduled` | `status` (`PENDING_DELETION`), `deleteAfter` (ISO instant: request time + 30 days) |
| `AccountExport` | `format` (`educore.account-export.v1`), `exportedAt` (ISO instant), `profile` `{id, username, firstName, lastName, studentNumber, role, ipAddress, status}`, `enrollments[]` `{courseId, courseName, term, instructor, enrolledAt (ISO local date-time)}` (oldest first), `securityEvents[]` `{type, at (ISO instant), involvement (ACTOR, TARGET, ACTOR_AND_TARGET), ip (only when the account itself acted, else null)}` (newest first, at most 10 000), `securityEventsTruncated` (boolean) |

Restore-only scope: an account in `PENDING_DELETION` can still log in (`POST /auth/login` 200 with
`user.status: PENDING_DELETION`) and refresh until `deleteAfter`. With such a session only `GET /api/v1/me`,
`POST /api/v1/me/restore` (with the current password) and `POST /api/v1/auth/logout` work; every other request, `GET /api/v1/auth/me`
included, answers 403 `account/pending-deletion`. After `deleteAfter` login answers 401
`auth/invalid-credentials` and bearer tokens 401 `auth/unauthenticated`; the account is purged by the nightly
job. `DEACTIVATED` accounts cannot log in at all (401 `auth/invalid-credentials`).

Errors added in P7:

| Status | Code | When |
|---|---|---|
| 400 | `account/confirmation-mismatch` | `POST .../purge` with a `confirm` other than the account's username |
| 403 | `account/pending-deletion` | Any request outside the restore-only scope by an account in its deletion grace period |
| 409 | `account/last-admin` | Also: the last active ADMIN requests their own deletion (`DELETE /api/v1/me`), or is hard-deleted |
| 409 | `account/not-restorable` | `POST /api/v1/me/restore` or the admin restore for an account that can no longer be restored |
| 429 | `rate-limit/exceeded` | Also: a second `GET /api/v1/me/export` within a minute (`Retry-After`) |

Webhook `account.deleted` data: `{accountId, mode: SOFT}` for a soft delete (unchanged) and
`{accountId, mode: HARD}` after a purge (ADMIN `POST .../purge` or the grace period ended).

### Changes for the frontend (P7)

- New shape fields: `UserView.status`; `Profile.status`, `Profile.deleteAfter`; `Account.status`,
  `Account.deleteAfter`; `SecurityEvent.actorPseudonym`, `SecurityEvent.targetPseudonym` (ids are null for
  purged accounts).
- New routes: `DELETE /api/v1/me` (body `{currentPassword}`, 202), `POST /api/v1/me/restore`,
  `GET /api/v1/me/export` (download), `POST /api/v1/admin/accounts/{accountId}/restore`.
- `DELETE /api/v1/admin/accounts/{accountId}` keeps its default (soft); hard delete needs
  `?mode=hard&confirm=<username>` (replaced in fix1 by `POST .../purge` with `{confirm}` in the body).
- `deleted=true` on the admin listings now also returns `PENDING_DELETION` accounts; show `status` and
  `deleteAfter` and offer restore.
- On login or bootstrap with `user.status = PENDING_DELETION`, or on any 403 `account/pending-deletion`, show the
  "deletion scheduled" screen (`GET /api/v1/me` gives `deleteAfter`) with restore and logout only. After
  `DELETE /api/v1/me` the refresh cookie is cleared and (since fix1) the current access token stops working at
  once; the user logs in again to restore, with the current password (see "Security fixes (fix1)").
