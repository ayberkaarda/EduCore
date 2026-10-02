# 0014. Fail fast on missing production variables with an `EnvironmentPostProcessor`

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-06

## Context

In `prod`, several variables are mandatory: `EDUCORE_DB_URL`, `EDUCORE_DB_USERNAME`, `EDUCORE_DB_PASSWORD`,
`EDUCORE_JWT_SECRET`, `EDUCORE_LOGIN_PEPPER`, `EDUCORE_ENCRYPTION_KEY`, `EDUCORE_BOOTSTRAP_ADMIN_USERNAME` and
`EDUCORE_BOOTSTRAP_ADMIN_PASSWORD`. Bean-level validation reports only the first failing bean
(often the data source), hiding the remaining missing variables and requiring one restart per variable.

## Decision

`com.educore.config.ProdStartupGuard` implements `EnvironmentPostProcessor` and is registered in
`META-INF/spring.factories`. When the `prod` profile is active it checks all required variables before any
bean is created and fails startup with one message naming every missing variable. It needs no database.

## Consequences

Positive:

- Operators see all configuration problems at once.
- No partially initialised application with missing secrets ever starts in production.

Negative:

- The list of required variables is maintained in code in addition to `.env.example`.

Revert: remove the `spring.factories` entry; bean validation then fails one variable at a time.

## References

- [`src/main/java/com/educore/config/ProdStartupGuard.java`](../../src/main/java/com/educore/config/ProdStartupGuard.java)
- [`src/main/resources/META-INF/spring.factories`](../../src/main/resources/META-INF/spring.factories)
- [`src/test/java/com/educore/config/ProdStartupGuardIT.java`](../../src/test/java/com/educore/config/ProdStartupGuardIT.java)
- [`.env.example`](../../.env.example)
