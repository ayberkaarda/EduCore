# 0038. A least-privilege database role for the application

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-92

## Context

The compose image creates `POSTGRES_USER` as a superuser, and the backend connected with exactly that role. Any
SQL-level foothold therefore had more power than the application needs: a future injection, a compromised
dependency with JDBC access, or a tampered migration could run `COPY ... TO PROGRAM`, read `pg_authid`, create
extensions or edit the audit trail without trace (threat model R-23, attack chain AC-15).

## Decision

- The backend's connection pool uses a runtime role (`EDUCORE_DB_APP_USERNAME`) with DML, sequence and default
  privileges only, defined in `infra/postgres/app-role.sql`. `infra/postgres/init/01-roles.sh` creates it on the
  first initialisation of an empty database and is idempotent. An existing database gets it once by hand
  (`docs/ops/UPGRADE.md`).
- Flyway migrates as the owner through `educore.database.migration-username/password`, applied by
  `MigrationRoleFlywayConfig`, a Flyway configuration customizer. `spring.flyway.user` is not used, because an
  empty value makes Boot derive a data source with an empty user.
- In `prod`, `ProdStartupGuard` requires both roles and refuses identical ones.
- Outside `prod` the pool falls back to `EDUCORE_DB_USERNAME`, so a local run with one user and the tests need no
  second role. Production-profile test starts create the role with the same script.

## Consequences

Positive:

- The application's own connections cannot run DDL, create extensions or reach server-side programs.
  `DatabaseRolesIT` runs nine forbidden statements through the application's pool and expects each to fail.

Negative:

- One more secret pair to manage, and a manual step for existing databases.
- The backup sidecar still dumps as the owner; a read-only dump role is BACKLOG B-091.

## References

- [`infra/postgres/app-role.sql`](../../infra/postgres/app-role.sql)
- [`infra/postgres/init/01-roles.sh`](../../infra/postgres/init/01-roles.sh)
- [`src/main/java/com/educore/config/MigrationRoleFlywayConfig.java`](../../src/main/java/com/educore/config/MigrationRoleFlywayConfig.java)
- [`src/main/java/com/educore/config/ProdStartupGuard.java`](../../src/main/java/com/educore/config/ProdStartupGuard.java)
- [`src/test/java/com/educore/DatabaseRolesIT.java`](../../src/test/java/com/educore/DatabaseRolesIT.java)
- [`src/test/java/com/educore/config/ProdStartupGuardIT.java`](../../src/test/java/com/educore/config/ProdStartupGuardIT.java)
- [`docs/ops/UPGRADE.md`](../ops/UPGRADE.md)
- Related: [0013](0013-flyway-adoption-and-dev-seed.md), [0014](0014-prod-startup-guard.md)
