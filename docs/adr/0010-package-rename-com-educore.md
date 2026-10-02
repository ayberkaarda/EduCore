# 0010. Package root `com.educore`, artifact `educore`, main class `EduCoreApplication`

- Status: Accepted
- Date: 2026-09-25
- Original decision: D-10

## Context

The code base carried a package name, Maven coordinates and main class that did not match the product name,
which made logs, stack traces and configuration keys inconsistent with the documentation.

## Decision

All Java code lives under `com.educore` in feature-first packages (`account`, `auth`, `common`, `config`,
`course`, `enrollment`, `ingestion`, `ipaccess`, `lifecycle`, `publicapi`, `ratelimit`, `security`, `weather`,
`webhook`, plus the shared `entity`, `repository` and `service` packages). The Maven coordinates are
`com.educore:educore`, the main class is `com.educore.EduCoreApplication`, and application settings live under
the `educore.*` prefix.

## Consequences

Positive:

- Consistent naming across code, logs, configuration and documentation.
- Feature packages keep related controller, service, repository and DTO classes together.

Negative:

- A one-time, repository-wide rename that touches every source file and invalidates in-flight branches.

## References

- [`pom.xml`](../../pom.xml)
- [`src/main/java/com/educore/EduCoreApplication.java`](../../src/main/java/com/educore/EduCoreApplication.java)
- [`src/test/java/com/educore/architecture/ArchitectureTest.java`](../../src/test/java/com/educore/architecture/ArchitectureTest.java)
- [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md)
