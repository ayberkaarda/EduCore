# API Routes (contract since P3)

Base URL in development: `http://localhost:8081`. Every route except the session endpoints needs
`Authorization: Bearer <accessToken>`. Roles per route are defined in
[`docs/security/RBAC_MATRIX.md`](../security/RBAC_MATRIX.md). Request bodies are JSON
(`Content-Type: application/json`); unknown fields are ignored, so server-owned fields (`id`, `role`,
`deleted`, `password`, `mustChangePassword`, `startIp`, `endIp`, `accountId`) can never be set through a body
unless a row below lists them.

## Old route → new route

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
| `GET /api/v1/ip-blocks` and `GET /api/v1/ips` | `GET /api/v1/admin/ip-rules` | GET | ADMIN (was any authenticated) | `IpRule[]`; `startIp`/`endIp` no longer exposed. |
| `POST /api/v1/ip-blocks` `{type, originalValue}` | `POST /api/v1/admin/ip-rules` `{type, originalValue}` | POST | ADMIN (was any authenticated) | 201 + `IpRule` (was 200 + entity); errors are problems (was `{"error"}`). |
| `DELETE /api/v1/ip-blocks/{id}` | `DELETE /api/v1/admin/ip-rules/{ipRuleId}` | DELETE | ADMIN (was any authenticated) | 204, no body; 404 for an unknown id. |
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
| GET | `/api/v1/admin/accounts` | ADMIN | query `search=""`, `deleted=false`, `page=0`, `size=10` | 200 `PageResponse<Account>` |
| GET | `/api/v1/admin/accounts/students` | ADMIN | query `search=""`, `deleted=false`, `page=0`, `size=10`, `direction=asc\|desc` | 200 `PageResponse<Account>` (role USER only) |
| POST | `/api/v1/admin/accounts/students` | ADMIN | `CreateStudentRequest` | 201 `CreatedStudent` |
| PUT | `/api/v1/admin/accounts/{accountId}` | ADMIN | `UpdateStudentRequest` | 200 `Account` |
| DELETE | `/api/v1/admin/accounts/{accountId}` | ADMIN | — | 204 (soft delete; repeat is a no-op) |
| PUT | `/api/v1/admin/accounts/{accountId}/role` | ADMIN | `ChangeRoleRequest` | 200 `Account` |
| GET | `/api/v1/admin/accounts/{accountId}/enrollments` | ADMIN | — | 200 `Course[]` |
| POST | `/api/v1/admin/accounts/{accountId}/enrollments` | ADMIN | `EnrollRequest` | 201 `Course` |
| DELETE | `/api/v1/admin/accounts/{accountId}/enrollments/{courseId}` | ADMIN | — | 204 (no-op if not enrolled) |
| POST | `/api/v1/admin/courses` | ADMIN | `CourseRequest` | 201 `Course` |
| PUT | `/api/v1/admin/courses/{courseId}` | ADMIN | `CourseRequest` | 200 `Course` |
| DELETE | `/api/v1/admin/courses/{courseId}` | ADMIN | — | 204 |
| GET | `/api/v1/admin/ip-rules` | ADMIN | — | 200 `IpRule[]` (by id) |
| POST | `/api/v1/admin/ip-rules` | ADMIN | `IpRuleRequest` | 201 `IpRule` |
| DELETE | `/api/v1/admin/ip-rules/{ipRuleId}` | ADMIN | — | 204 |
| GET | `/api/v1/admin/job-logs` | ADMIN | — | 200 `JobLog[]` (newest first) |
| DELETE | `/api/v1/admin/job-logs` | ADMIN | query `ids=1,2,3` (1–500 ids; unknown ids skipped) | 204 |
| GET | `/api/v1/admin/security-events` | ADMIN | query `page=0`, `size=20` (max 100) | 200 `PageResponse<SecurityEvent>` |

`page` is zero-based and clamped to ≥ 0; `size` is clamped to 1–100 on every paged route.

## Shapes

Requests (constraints are the current minimal Bean Validation; full validation arrives in P4):

| Name | Fields |
|---|---|
| `UpdateProfileRequest` | `firstName` (required, ≤ 100), `lastName` (required, ≤ 100) |
| `EnrollRequest` | `courseId` (required, number) |
| `CreateStudentRequest` | `firstName` (required, ≤ 100), `lastName` (≤ 100), `studentNumber` (≤ 32, optional), `username` (≤ 100, optional; generated from the first name when absent), `ipAddress` (optional IPv4 inside an IP rule) |
| `UpdateStudentRequest` | `firstName` (≤ 100), `lastName` (≤ 100), `studentNumber` (≤ 32), `ipAddress` (IPv4 inside an IP rule; blank or absent removes it). All four are replaced. |
| `ChangeRoleRequest` | `role`: `"ADMIN"` or `"USER"` (required) |
| `CourseRequest` | `name` (required, ≤ 255, unique), `term` (≤ 255), `instructor` (≤ 255) |
| `IpRuleRequest` | `type`: `"STATIC"`, `"RANGE"` or `"CIDR"` (required); `originalValue` (required, ≤ 64): `192.168.1.10`, `192.168.1.1-192.168.1.10` or `192.168.1.0/24` |

Responses:

| Name | Fields |
|---|---|
| `UserView` | `id`, `firstName`, `role`, `mustChangePassword` (auth endpoints only) |
| `Profile` | `id`, `username`, `firstName`, `lastName`, `studentNumber`, `role` |
| `Account` | `id`, `username`, `firstName`, `lastName`, `studentNumber`, `role`, `ipAddress` |
| `CreatedStudent` | `Account` fields + `temporaryPassword` (24 chars, shown once to the ADMIN; the student must change it at first login) |
| `Course` | `id`, `name`, `term`, `instructor` |
| `IpRule` | `id`, `type`, `originalValue` |
| `JobLog` | `id`, `fileName`, `entityType`, `status`, `successfulRecords`, `failedRecords`, `createdAt` (ISO local date-time), `detailedLogs` |
| `SecurityEvent` | `id`, `type`, `actorAccountId`, `targetAccountId`, `ip`, `requestId`, `at` (ISO instant), `details` (object or null) |
| `CityWeather` | `city`, `temperature`, `windSpeed`, `weatherCode`, `description` |
| `PageResponse<T>` | `content` (`T[]`), `page`, `size`, `totalElements`, `totalPages` |

No response contains a password hash, `deleted`, `mustChangePassword` (except `UserView`) or nested entities.

## Errors

Feature routes answer `application/problem+json` with `{type, title, status}`; `type` ends with the code
below (`/problems/<code>`). `title` is a fixed English sentence safe to show.

| Status | Code | When |
|---|---|---|
| 400 | `request/invalid` | Malformed JSON, missing/invalid required field, unknown enum value, bad query parameter |
| 400 | `account/ip-address-invalid` | `ipAddress` is not an IPv4 address |
| 400 | `account/ip-address-not-allocatable` | `ipAddress` is outside every IP rule |
| 400 | `ip-rule/invalid` | `originalValue` does not match `type` |
| 404 | `account/not-found`, `course/not-found`, `ip-rule/not-found` | Unknown id; the enrollment routes also treat a soft-deleted account as unknown |
| 409 | `account/self-role-change` | ADMIN changes their own role |
| 409 | `account/self-delete` | ADMIN deletes their own account |
| 409 | `account/last-admin` | The change would leave no active ADMIN |
| 409 | `account/student-number-taken`, `account/username-taken`, `account/ip-address-taken` | Uniqueness |
| 409 | `enrollment/already-enrolled` | Duplicate enrollment |
| 409 | `request/concurrent-modification` | Another request changed the same account first (optimistic lock); reload and retry |

Other statuses:
- 401 with an empty body: missing, invalid or expired access token (refresh, then retry once).
- 403 with Spring's default error JSON (`{timestamp, status, error, path}`): authenticated but not allowed.
- 409 `{"error": "..."}` (legacy handler until P4): a database constraint, e.g. deleting a course that still has
  enrollments or creating a course whose name exists.
- Session endpoint errors are unchanged (`auth/*` problems, see P2).
