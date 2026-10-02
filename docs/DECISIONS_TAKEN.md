# Decision Index

Architecture decisions are recorded as Architecture Decision Records in [`docs/adr/`](adr/README.md). Each
record holds the context, the decision, its consequences and how to revert it. The short decision ids below
(`D-nn`, `D-NEW-nn`) are used in commit messages, [`BACKLOG.md`](BACKLOG.md) and the security documents. This
file maps each id to its record.

| Id | Decision | Record |
|---|---|---|
| D-01 | Remove the Config Server; environment-first configuration | [ADR 0001](adr/0001-remove-config-server.md) |
| D-02 | Remove the SOAP endpoint `/ws/**` | [ADR 0002](adr/0002-remove-soap-endpoint.md) |
| D-03 | Prerendered public pages, client-side SPA for the application | [ADR 0003](adr/0003-prerendered-public-site-and-spa.md) |
| D-04 | Delete leftover frontend files | [ADR 0004](adr/0004-delete-frontend-leftover-files.md) |
| D-05 | Weather widget proxied and authenticated through the backend | [ADR 0005](adr/0005-weather-proxy-through-backend.md) |
| D-06 | Spring Batch import jobs replace the hand-written importer | [ADR 0006](adr/0006-spring-batch-import-jobs.md) |
| D-07 | Delegating bcrypt encoder with upgrade on login | [ADR 0007](adr/0007-password-hashing.md) |
| D-08 | Short-lived access tokens and rotating refresh tokens | [ADR 0008](adr/0008-access-and-refresh-tokens.md) |
| D-09 | Split the student IP allow-list from request-level deny rules | [ADR 0009](adr/0009-split-ip-allow-list-and-deny-rules.md) |
| D-10 | Package root `com.educore` | [ADR 0010](adr/0010-package-rename-com-educore.md) |
| D-NEW-01 | Apply the brand identity early | [ADR 0011](adr/0011-brand-identity-and-theme-persistence.md) |
| D-NEW-02 | Phase delivery and commit procedure | Process agreement, not an architectural decision; no ADR |
| D-NEW-03 | Persist only the theme choice in `localStorage` | [ADR 0011](adr/0011-brand-identity-and-theme-persistence.md) |
| D-NEW-04 | Dependency baseline: Spring Boot 3.5 | [ADR 0012](adr/0012-dependency-baseline-spring-boot-3-5.md) |
| D-NEW-05 | Flyway adoption of legacy databases, repeatable dev seed | [ADR 0013](adr/0013-flyway-adoption-and-dev-seed.md) |
| D-NEW-06 | Production startup guard | [ADR 0014](adr/0014-prod-startup-guard.md) |
| D-NEW-07 | One URL prefix per role | [ADR 0015](adr/0015-role-based-route-layout.md) |
| D-NEW-08 | Weather route requires authentication | [ADR 0005](adr/0005-weather-proxy-through-backend.md) |
| D-NEW-09 | ADMIN-only listings without internal flags | [ADR 0016](adr/0016-admin-only-listings.md) |
| D-NEW-10 | Self-change and last-ADMIN guards | [ADR 0017](adr/0017-self-change-and-last-admin-guards.md) |
| D-NEW-11 | One audit writer | [ADR 0018](adr/0018-single-audit-writer.md) |
| D-NEW-12 | RFC 9457 Problem Details as the only error shape | [ADR 0019](adr/0019-problem-details-error-model.md) |
| D-NEW-13 | Strict paging and whitelisted sorting | [ADR 0020](adr/0020-paging-sorting-and-input-validation.md) |
| D-NEW-14 | Allow-list input validation | [ADR 0020](adr/0020-paging-sorting-and-input-validation.md) |
| D-NEW-15 | Escaped LIKE patterns | [ADR 0021](adr/0021-query-construction-safety.md) |
| D-NEW-16 | Bytecode rule against runtime-built queries | [ADR 0021](adr/0021-query-construction-safety.md) |
| D-NEW-17 | Job-log JSON serialisation (superseded by the ingestion pipeline) | [ADR 0022](adr/0022-job-log-json-serialisation.md) |
| D-NEW-18 | Request size limits, strict JSON binding, problem-shaped CORS rejections | [ADR 0023](adr/0023-request-size-and-binding-limits.md) |
| D-NEW-19 | ECS structured logging with PII masking | [ADR 0024](adr/0024-structured-logging-and-pii-masking.md) |
| D-NEW-20 | Request and multipart size limits | [ADR 0023](adr/0023-request-size-and-binding-limits.md) |
| D-NEW-30 | File ingestion pipeline | [ADR 0026](adr/0026-ingestion-pipeline.md) |
| D-NEW-31 | Signed outbound webhooks | [ADR 0027](adr/0027-signed-outbound-webhooks.md) |
| D-NEW-40 | Anonymous public course catalog | [ADR 0028](adr/0028-public-course-catalog.md) |
| D-NEW-50 | Perimeter: HTTPS requirement, trusted proxies, rate limiting | [ADR 0025](adr/0025-perimeter-https-proxies-and-rate-limiting.md) |
| D-NEW-51 | Perimeter review fixes: deny order, address normalisation, admission store | [ADR 0025](adr/0025-perimeter-https-proxies-and-rate-limiting.md) |
| D-NEW-70 | Account lifecycle and 30-day grace period | [ADR 0029](adr/0029-account-lifecycle-and-grace-period.md) |
| D-NEW-71 | Audit pseudonymisation and retention | [ADR 0030](adr/0030-audit-pseudonymisation-and-retention.md) |
| D-NEW-72 | Self-service personal data export | [ADR 0031](adr/0031-personal-data-export.md) |
| D-NEW-80 | Login keys, per-pair lockout, progressive delay, unlock endpoint | [ADR 0032](adr/0032-login-keys-lockout-and-progressive-delay.md) |
| D-NEW-81 | Session epoch and password-protected restore | [ADR 0033](adr/0033-session-epoch-and-password-protected-restore.md) |
| D-NEW-82 | Server-enforced password-change scope | [ADR 0034](adr/0034-server-enforced-password-change-scope.md) |
| D-NEW-83 | Purge route, derived pseudonym key, explicit binding, dev-seed guard | [ADR 0035](adr/0035-purge-route-pseudonym-key-and-production-guards.md) |
| D-NEW-90 | Erase processed CSV files by default | [ADR 0036](adr/0036-erase-processed-csv-files.md) |
| D-NEW-91 | Erasure ledger replayed after every restore | [ADR 0037](adr/0037-erasure-ledger-replayed-after-restore.md) |
| D-NEW-92 | Least-privilege database runtime role | [ADR 0038](adr/0038-least-privilege-database-runtime-role.md) |
| D-NEW-93 | Ingestion lease fencing, webhook DNS deadline, edge logs without query strings | [ADR 0039](adr/0039-ingestion-lease-fencing-dns-deadline-and-edge-logs.md) |

A new decision gets the next free ADR number in [`docs/adr/`](adr/README.md) and, if it needs a short id, a row
here.
