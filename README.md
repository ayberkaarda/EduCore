# EduCore

**English** | [Türkçe](./README.tr.md)

EduCore is an educational management system built with a Spring Boot API and a React (Vite) frontend. It manages accounts, courses and enrollments, imports CSV files, and lets administrators configure IPv4 allocation rules for student accounts.

## Screenshots

| | |
| --- | --- |
| <img src="screenshots/login.png" width="100%" alt="Login"><br>Login | <img src="screenshots/dashboard.png" width="100%" alt="Dashboard"><br>Dashboard |
| <img src="screenshots/students.png" width="100%" alt="Students"><br>Students | <img src="screenshots/courses.png" width="100%" alt="Courses"><br>Courses |
| <img src="screenshots/job-logs.png" width="100%" alt="Job logs"><br>Job logs | |

## API overview

The versioned API uses four route groups:

- `/api/v1/auth` — login, refresh, logout, current authenticated user, and password change.
- `/api/v1/me` — the caller's profile and enrollments.
- `/api/v1/courses` — authenticated course catalog; `/api/v1/weather` also requires login.
- `/api/v1/admin/...` — ADMIN-only account/student listings and management, courses, IP rules, job logs, security events, and account enrollments.

Roles are `ADMIN` and `USER`. See the [API route contract](docs/api/ROUTES.md) for methods and payloads and the [RBAC matrix](docs/security/RBAC_MATRIX.md) for every route's access rules.

## Authorization and security

`ADMIN` can use administrative routes. `USER` can access their own profile and enrollments, plus the authenticated catalog and weather endpoints; account and student listings are ADMIN-only. Anonymous access is limited to the session endpoints and documented public URL rules.

Authorization is enforced by URL rules and Spring method security. The typed principal is `AuthenticatedUser(id, username, role)`; roles are loaded from the database. Self-service operations derive the account from that principal, while admin operations use guarded account IDs, preventing IDOR. Self-demotion, self-deletion, and demotion or deletion of the last active administrator are rejected. Account rows use optimistic locking to reject stale concurrent writes.

Administrator mutations are recorded in `security_event` in the same transaction as the change. Event types are `ACCOUNT_CREATED`, `ACCOUNT_UPDATED`, `ACCOUNT_DELETED`, `ROLE_CHANGED`, `ENROLLMENT_CHANGED`, `COURSE_CHANGED`, `IP_RULE_CHANGED`, and `JOB_LOGS_DELETED`. No-op requests do not create events.

Security work completed so far includes moving secrets to environment configuration, hardening authentication, enforcing authorization, and removing SOAP. Remaining planned work: P4 input validation and Problem Details; P5 edge hardening, network IP blocking and rate limiting; P6 ingestion lifecycle; P7 account lifecycle; P8 frontend platform; P9 SEO. The student IP rules currently validate account IP assignments and do not block network traffic. See the [2026-09-25 baseline audit](docs/audit/2026-09-25-baseline.md).

## Source layout

Backend code is organized by feature: `account`, `auth`, `course`, `enrollment`, `ipaccess`, and `ingestion`. Shared web/API behavior lives in `common`; authentication and access controls live in `security`, with audit services and security-event handling under `security.audit`. Configuration, persistence entities, repositories, and DTOs have their own packages. Database migrations are under `src/main/resources/db/migration`.

## Tests

The backend has unit tests and integration tests using Testcontainers with PostgreSQL. The P3 verification report records 60 unit tests (including 2 `ApiExceptionHandlerTest` cases) and 248 integration tests, 308 total, with no failures, errors, or skips. Run the full verification from the repository root; Docker must be available for Testcontainers:

```bash
./mvnw verify
```

On Windows, run `mvnw.cmd verify`.

## Quick start

Start the services with Docker Compose after creating `.env` from `.env.example` and filling in the required environment variables:

```bash
cp .env.example .env
docker compose up --build
```

The frontend is at `http://localhost:3000`; the API is at `http://localhost:8081`. Configuration and setup details are in the environment template and application profile files.
