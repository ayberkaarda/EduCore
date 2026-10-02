# 0035. Purge route with a body confirmation, a derived pseudonym key, explicit variable binding and a dev-seed guard

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-83

## Context

Four findings of the threat model share one theme: data that should be gone, or settings that should hold in
production, depended on details that were easy to get wrong.

- The immediate purge was `DELETE /api/v1/admin/accounts/{id}?mode=hard&confirm=<username>`. The username
  therefore appeared in the request line that every proxy logs, after the database had forgotten the person
  (R-22, AC-14).
- Audit pseudonyms of purged accounts were an HMAC under the same key as the login username hash. A login for
  the username `account:<id>` wrote a `usernameHash` equal to that account's pseudonym, which undid the
  pseudonymisation (R-21, AC-12).
- `EDUCORE_SEO_BASE_URL` was bound only through Spring's relaxed binding. It was reported as unbound (R-19,
  AC-05); that did not reproduce, because Spring Boot maps the variable through its legacy underscore form, but
  the binding was implicit and untested.
- A database once started under `dev` carried the demo accounts. After a switch to `prod` the bootstrap ADMIN was
  skipped because an ADMIN already existed (R-02, AC-03).

## Decision

- An immediate purge is `POST /api/v1/admin/accounts/{id}/purge` with `{"confirm": "<username>"}` in the JSON
  body. `DELETE /api/v1/admin/accounts/{id}` accepts only `mode=soft`; any other mode is 400 `request/invalid`.
- `Pseudonyms` uses a key derived from the pepper with a label (`HMAC(label, pepper)`), separate from the
  username hash. Pseudonyms written before the change are not recomputed.
- `application.yml` binds `EDUCORE_SEO_BASE_URL`, the CORS list and the bootstrap variables through explicit
  placeholders. `EnvironmentVariableBindingTest` requires a placeholder for every `EDUCORE_*` variable documented
  in `.env.example` or passed by compose. In `prod`, `ProdStartupGuard` requires `EDUCORE_SEO_BASE_URL` to be an
  https origin that is not localhost.
- In `prod`, `DevSeedAccountGuard` refuses to start while a seed username still carries its seed password hash or
  seed student number. The remedy is in `docs/ops/UPGRADE.md`.

## Consequences

Positive:

- Request bodies are not logged by the edge or the backend, so purge confirmations stay out of logs.
- One secret still serves both purposes, without a second key to manage.
- An explicit placeholder is visible in review and checked by a test instead of depending on a naming rule.
- A promoted development database cannot run in production with the demo credentials.

Negative:

- Clients of the old hard-delete query parameters must move to the new route.
- `details.usernameHash` of failed logins is still returned to ADMINs. It no longer equals a pseudonym, but it
  still correlates failed logins of one username (BACKLOG B-083).

## References

- [`src/main/java/com/educore/account/AccountAdminController.java`](../../src/main/java/com/educore/account/AccountAdminController.java)
- [`src/main/java/com/educore/lifecycle/Pseudonyms.java`](../../src/main/java/com/educore/lifecycle/Pseudonyms.java)
- [`src/main/java/com/educore/config/ProdStartupGuard.java`](../../src/main/java/com/educore/config/ProdStartupGuard.java)
- [`src/main/java/com/educore/config/DevSeedAccountGuard.java`](../../src/main/java/com/educore/config/DevSeedAccountGuard.java)
- [`src/test/java/com/educore/auth/PseudonymDomainSeparationTest.java`](../../src/test/java/com/educore/auth/PseudonymDomainSeparationTest.java)
- [`src/test/java/com/educore/config/EnvironmentVariableBindingTest.java`](../../src/test/java/com/educore/config/EnvironmentVariableBindingTest.java)
- [`src/test/java/com/educore/config/DevSeedAccountGuardTest.java`](../../src/test/java/com/educore/config/DevSeedAccountGuardTest.java)
- [`src/test/java/com/educore/FlywayProfileSwitchIT.java`](../../src/test/java/com/educore/FlywayProfileSwitchIT.java)
- [`src/test/java/com/educore/lifecycle/AccountLifecycleIT.java`](../../src/test/java/com/educore/lifecycle/AccountLifecycleIT.java)
- Related: [0014](0014-prod-startup-guard.md), [0030](0030-audit-pseudonymisation-and-retention.md)
