# Architecture Decision Records

Each record describes one decision (or a small group of closely related decisions) with its context and
consequences. The "Original id" column refers to the decision ids used in commit messages, the backlog and the
security documents; [`docs/DECISIONS_TAKEN.md`](../DECISIONS_TAKEN.md) maps each id to its record. The system
these decisions shape is described in [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md).

Format: `NNNN-kebab-title.md` with Title, Status, Date, Context, Decision, Consequences and References. A new
decision gets the next free number; a replaced decision keeps its file and changes its status to
"Superseded by" with a link.

| Number | Title | Status | Original id |
|---|---|---|---|
| [0001](0001-remove-config-server.md) | Remove the Config Server and use environment-first configuration | Accepted | D-01 |
| [0002](0002-remove-soap-endpoint.md) | Remove the SOAP endpoint `/ws/**` | Accepted | D-02 |
| [0003](0003-prerendered-public-site-and-spa.md) | Build-time prerendering for public pages, client-side SPA for the application | Accepted | D-03 |
| [0004](0004-delete-frontend-leftover-files.md) | Delete leftover frontend files | Accepted | D-04 |
| [0005](0005-weather-proxy-through-backend.md) | Keep the weather widget, proxied and authenticated through the backend | Accepted | D-05, D-NEW-08 |
| [0006](0006-spring-batch-import-jobs.md) | Replace the hand-written multi-threaded importer with Spring Batch jobs | Accepted | D-06 |
| [0007](0007-password-hashing.md) | Password hashing with a delegating bcrypt encoder and upgrade on login | Accepted | D-07 |
| [0008](0008-access-and-refresh-tokens.md) | Short-lived HS256 access tokens and rotating opaque refresh tokens | Accepted | D-08 |
| [0009](0009-split-ip-allow-list-and-deny-rules.md) | Split the student IP allow-list from request-level IP deny rules | Accepted | D-09 |
| [0010](0010-package-rename-com-educore.md) | Package root `com.educore`, artifact `educore`, main class `EduCoreApplication` | Accepted | D-10 |
| [0011](0011-brand-identity-and-theme-persistence.md) | Apply the brand identity early and persist only the theme choice in `localStorage` | Accepted | D-NEW-01, D-NEW-03 |
| [0012](0012-dependency-baseline-spring-boot-3-5.md) | Dependency baseline: Spring Boot 3.5 and matching libraries | Accepted | D-NEW-04 |
| [0013](0013-flyway-adoption-and-dev-seed.md) | Flyway adoption of legacy databases and a repeatable dev seed | Accepted | D-NEW-05 |
| [0014](0014-prod-startup-guard.md) | Fail fast on missing production variables with an `EnvironmentPostProcessor` | Accepted | D-NEW-06 |
| [0015](0015-role-based-route-layout.md) | One URL prefix per role | Accepted | D-NEW-07 |
| [0016](0016-admin-only-listings.md) | Student and account listings are ADMIN-only; responses expose no internal flags | Accepted (amended by 0020, 0029) | D-NEW-09 |
| [0017](0017-self-change-and-last-admin-guards.md) | Self-change and last-ADMIN guards answer 409 and serialise on ADMIN rows | Accepted | D-NEW-10 |
| [0018](0018-single-audit-writer.md) | One audit writer for the security event trail | Accepted | D-NEW-11 |
| [0019](0019-problem-details-error-model.md) | RFC 9457 Problem Details as the only error shape | Accepted | D-NEW-12 |
| [0020](0020-paging-sorting-and-input-validation.md) | Strict paging, whitelisted sorting and allow-list input validation | Accepted | D-NEW-13, D-NEW-14 |
| [0021](0021-query-construction-safety.md) | Escaped LIKE patterns and a bytecode rule against runtime-built queries | Accepted | D-NEW-15, D-NEW-16 |
| [0022](0022-job-log-json-serialisation.md) | Serialise job-log entries with Jackson and keep exception text out of job logs | Superseded by [0026](0026-ingestion-pipeline.md) | D-NEW-17 |
| [0023](0023-request-size-and-binding-limits.md) | Request size limits, strict JSON binding and problem-shaped CORS rejections | Accepted | D-NEW-18, D-NEW-20 |
| [0024](0024-structured-logging-and-pii-masking.md) | ECS structured logging in production with PII masking | Accepted | D-NEW-19 |
| [0025](0025-perimeter-https-proxies-and-rate-limiting.md) | Perimeter: HTTPS requirement, trusted proxies, IP deny order and rate limiting | Accepted | D-NEW-50, D-NEW-51 |
| [0026](0026-ingestion-pipeline.md) | File ingestion pipeline with private snapshots, leases and an idempotency ledger | Accepted | D-NEW-30 |
| [0027](0027-signed-outbound-webhooks.md) | Signed outbound webhooks with a bounded queue, fenced claims and an SSRF guard | Accepted | D-NEW-31 |
| [0028](0028-public-course-catalog.md) | Anonymous public course catalog with stable slugs and a catalog revision | Accepted | D-NEW-40 |
| [0029](0029-account-lifecycle-and-grace-period.md) | Account lifecycle states, a 30-day deletion grace period and a nightly purge | Accepted | D-NEW-70 |
| [0030](0030-audit-pseudonymisation-and-retention.md) | Pseudonymise purged accounts in the audit trail and bound retention | Accepted | D-NEW-71 |
| [0031](0031-personal-data-export.md) | Self-service personal data export | Accepted | D-NEW-72 |
| [0032](0032-login-keys-lockout-and-progressive-delay.md) | Login throttling keyed by client network, per-pair lockout and a progressive delay | Accepted | D-NEW-80 |
| [0033](0033-session-epoch-and-password-protected-restore.md) | Session epoch as a session boundary, and a password-protected restore | Accepted | D-NEW-81 |
| [0034](0034-server-enforced-password-change-scope.md) | Server-enforced password-change scope for temporary and bootstrap passwords | Accepted | D-NEW-82 |
| [0035](0035-purge-route-pseudonym-key-and-production-guards.md) | Purge route with a body confirmation, a derived pseudonym key, explicit variable binding and a dev-seed guard | Accepted | D-NEW-83 |
| [0036](0036-erase-processed-csv-files.md) | Erase processed CSV files by default | Accepted | D-NEW-90 |
| [0037](0037-erasure-ledger-replayed-after-restore.md) | An erasure ledger replayed after every restore | Accepted | D-NEW-91 |
| [0038](0038-least-privilege-database-runtime-role.md) | A least-privilege database role for the application | Accepted | D-NEW-92 |
| [0039](0039-ingestion-lease-fencing-dns-deadline-and-edge-logs.md) | Lease fencing for ingestion, webhook DNS inside the deadline, and edge logs without query strings | Accepted | D-NEW-93 |

Not recorded as an ADR: D-NEW-02 (development workflow and commit procedure), which is a process agreement
rather than an architectural decision.
