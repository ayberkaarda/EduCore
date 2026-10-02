# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Architecture decisions behind these changes are
recorded in [docs/adr/](docs/adr/README.md).

## Unreleased

## 1.0.0 - 2026-10-02

First versioned release. It turns the earlier prototype into a hardened application, delivered in eleven
phases (P0 to P10). Upgrading an existing database needs manual steps: a one-time out-of-order Flyway start,
the least-privilege runtime role and the erasure ledger volume ([docs/ops/UPGRADE.md](docs/ops/UPGRADE.md)).

### Added
- **Configuration and data (P1):**
  - `dev`, `test` and `prod` profiles with environment-first configuration and a validated `educore.*` property tree.
  - `ProdStartupGuard` fails fast when production variables are missing.
  - Flyway migrations (V1–V42) with `ddl-auto=validate`.
  - A Testcontainers-based integration test harness.
- **Authentication (P2):**
  - 15-minute access tokens and rotating refresh tokens in an HttpOnly, Secure, SameSite=Strict cookie, with reuse detection per token family.
  - Login throttling per IP and per account, with an HMAC-peppered `login_attempt` record.
  - Delegating bcrypt password encoder with upgrade on login.
  - Forced password change for bootstrap and temporary passwords.
- **Authorization (P3):**
  - A machine-readable RBAC matrix enforced by URL rules and `@PreAuthorize`, verified by `AuthorizationMatrixIT`.
  - Optimistic locking on accounts.
  - Self-change and last-ADMIN guards.
  - A single audit writer for the `security_event` trail.
- **Input, errors and logs (P4):**
  - RFC 9457 problem details as the only error shape, with no stack traces or exception messages.
  - Allow-list validation, whitelisted sorting and strict paging.
  - Request size limits.
  - ECS structured logs with request ids and PII masking.
- **Perimeter (P5):**
  - CORS allow-list and security headers on every response.
  - HTTPS requirement in `prod` and trusted-proxy handling of forwarded headers.
  - Per-IP and per-account rate limiting.
  - Request-level IP deny rules with automatic blocking after repeated failed logins.
  - A production nginx edge (TLS 1.2/1.3, HSTS, SPA CSP) with certificates from a host directory or a certbot sidecar.
- **Ingestion and integrations (P6):**
  - An idempotent CSV ingestion pipeline (private snapshots, leases, ledger, row-level reports).
  - Signed outbound webhooks (`X-EduCore-Signature`) with retries, fenced delivery claims and an SSRF guard.
- **Data lifecycle and operations (P7):**
  - Account lifecycle (`ACTIVE`, `DEACTIVATED`, `PENDING_DELETION`, `DELETED`) with a 30-day grace period.
  - A nightly purge job and pseudonymisation of the audit trail.
  - Self-service personal data export.
  - Backup sidecar (daily `pg_dump`, 14 daily and 8 weekly, optional S3 copy) with restore and verification scripts.
  - Budget alerts (Terraform for AWS and GCP), container resource limits, log rotation, Prometheus alert examples.
  - Dependabot.
  - CycloneDX SBOMs, Trivy dependency and image scanning, and images pinned by digest that run as non-root.
- **Frontend platform (P8):**
  - React Router 7 framework mode with TypeScript, a single API client with refresh handling, and role-based route layouts.
  - Unit and component tests.
- **Public site (P9):**
  - Anonymous public course catalog API with stable slugs, ETags and sitemaps.
  - Prerendered Turkish and English public pages with canonical, hreflang, Open Graph and JSON-LD metadata.
  - `robots.txt`, `llms.txt` and `llms-full.txt`, plus Lighthouse budgets.
- **Assurance and release (P10):**
  - An attacker-mode test suite (JUnit tag `attack`), a threat model and abuse cases.
  - OWASP ZAP baseline scanning.
  - CI workflows: backend with a JaCoCo coverage gate, frontend, security, end-to-end and images.
  - OpenAPI document (`docs/api/openapi.yaml`, Swagger UI in `dev` only).
  - Architecture documentation and ADRs.
  - Production preflight and environment-documentation checks.

### Changed
- Package root renamed to `com.educore`, artifact `educore`, main class `EduCoreApplication`.
- All routes are versioned under `/api/v1`, with one prefix per role (`/api/v1/admin/**`, `/api/v1/me/**`, public routes). See [docs/api/ROUTES.md](docs/api/ROUTES.md).
- `/api/v1/admin/ip-rules` now manages request-level deny rules. The student IP allow-list moved to `/api/v1/admin/ip-allocations`.
- The hand-written multi-threaded importer is replaced by Spring Batch jobs inside the ingestion pipeline.
- `account.deleted` is replaced by `account.status` (migration V21).
- The weather widget is proxied and authenticated through the backend.
- Docker Compose publishes only the web edge:
  - Development uses port 3000. PostgreSQL and the API are available on 127.0.0.1 through `docker-compose.dev.yml`.
  - Production uses ports 80 and 443 through `docker-compose.prod.yml`.
  - The Actuator management port is never published.

### Removed
- Spring Cloud Config Server and the SOAP endpoint (`/ws/**`).
- Secrets and leftover files committed to the repository. The history purge procedure is in [docs/security/SECRET_ROTATION_AND_HISTORY_PURGE.md](docs/security/SECRET_ROTATION_AND_HISTORY_PURGE.md).

### Security
- Every secret present before P0 is treated as compromised and must be rotated ([docs/security/KEY_ROTATION.md](docs/security/KEY_ROTATION.md)).
- Dependency versions above the Spring Boot defaults close known HIGH/CRITICAL advisories in Tomcat, Jackson, the PostgreSQL driver, HttpCore 5 and Bouncy Castle. The Trivy gate in CI keeps that state.
- Findings of the threat model fixed before release (ADRs 0032–0039, [docs/security/ATTACK_RESULTS.md](docs/security/ATTACK_RESULTS.md)):
  - login lockout per (username, network) pair with a progressive delay, an unlock endpoint and an IPv6 /64 client key;
  - session epochs and a password-protected restore;
  - a server-enforced password-change scope;
  - purge confirmation in the request body, and a derived pseudonym key;
  - a dev-seed guard and an explicit `EDUCORE_SEO_BASE_URL` binding;
  - processed CSV files erased by default, and an erasure ledger replayed after restores;
  - a least-privilege database runtime role;
  - lease fencing for uploads and imports, webhook DNS inside the request deadline, and edge access logs without query strings.

