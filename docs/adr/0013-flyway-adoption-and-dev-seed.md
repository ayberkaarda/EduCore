# 0013. Flyway adoption of legacy databases and a repeatable dev seed

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-05

## Context

Databases created before Flyway always contain the V1 tables (built by Hibernate `ddl-auto`) but may or may
not contain the Spring Batch tables. Demo data for development must not become part of the versioned schema
history, because a database used under `dev` may later be started under `prod`.

## Decision

- `spring.flyway.baseline-on-migrate=true` with `baseline-version=1`: a non-empty legacy schema is recorded at
  version 1, and `V2__spring_batch_schema.sql` then adds only the missing Batch objects using
  `CREATE TABLE/SEQUENCE IF NOT EXISTS` with the official definitions.
- `spring.jpa.hibernate.ddl-auto=validate` in every profile; Flyway owns the schema.
- The development seed is the repeatable migration `db/seed/dev/R__dev_seed.sql` (idempotent,
  `ON CONFLICT DO NOTHING`), included in the Flyway locations only by the `dev` and `test` profiles.
- `prod` sets `spring.flyway.ignore-migration-patterns=*:future,repeatable:missing`, tolerating only the
  absent seed; any missing versioned migration still fails validation.

## Consequences

Positive:

- Legacy databases upgrade in place; a dev database can be promoted to prod without editing the schema
  history.
- Tests cover both paths (`FlywayLegacyAdoptionIT`, `FlywayProfileSwitchIT`).

Negative:

- Baselining trusts that a legacy schema really matches V1.
- Later migrations numbered below already applied ones (V12, V13, V21) need a one-time
  `SPRING_FLYWAY_OUT_OF_ORDER=true` start on older databases (`docs/ops/UPGRADE.md`).

## References

- [`src/main/resources/application.yml`](../../src/main/resources/application.yml)
- [`src/main/resources/application-prod.yml`](../../src/main/resources/application-prod.yml)
- [`src/main/resources/application-dev.yml`](../../src/main/resources/application-dev.yml)
- [`src/main/resources/db/migration/V2__spring_batch_schema.sql`](../../src/main/resources/db/migration/V2__spring_batch_schema.sql)
- [`src/main/resources/db/seed/dev/R__dev_seed.sql`](../../src/main/resources/db/seed/dev/R__dev_seed.sql)
- [`src/test/java/com/educore/FlywayLegacyAdoptionIT.java`](../../src/test/java/com/educore/FlywayLegacyAdoptionIT.java)
- [`src/test/java/com/educore/FlywayProfileSwitchIT.java`](../../src/test/java/com/educore/FlywayProfileSwitchIT.java)
- [`docs/ops/UPGRADE.md`](../ops/UPGRADE.md)
