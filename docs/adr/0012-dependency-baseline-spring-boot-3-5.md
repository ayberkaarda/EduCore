# 0012. Dependency baseline: Spring Boot 3.5 and matching libraries

- Status: Accepted
- Date: 2026-10-01
- Original decision: D-NEW-04

## Context

The project was on Spring Boot 3.3.1, Spring Cloud 2023.0.2 and jjwt 0.11.5. Several planned features depend
on newer releases (for example Boot's built-in structured JSON logging, available from 3.4), and older lines
receive fewer fixes. A major upgrade to Boot 4.x would be a larger, separately approved change.

## Decision

Versions were checked on Maven Central on 2026-10-01 and set to:

- Spring Boot parent 3.5.16 (newest 3.x release);
- Spring Cloud BOM 2025.0.3 (the train built for Boot 3.5; OpenFeign 4.3.3);
- jjwt 0.12.7 (newest 0.12.x; the code uses the 0.12 builder and parser API);
- JaCoCo 0.8.15;
- Flyway, Testcontainers, Hibernate and Spring Batch versions come from the Boot BOM.

## Consequences

Positive:

- Current security fixes and access to built-in structured logging ([ADR 0024](0024-structured-logging-and-pii-masking.md)).
- Fewer explicitly pinned versions; the Boot BOM keeps transitive versions aligned.

Negative:

- The jjwt 0.12 API differs from 0.11; `JwtService` was rewritten accordingly.
- Boot 4.x remains a future migration.

Revert: set the parent version and `spring-cloud.version` back in `pom.xml` and restore the 0.11 calls in
`JwtService`.

## References

- [`pom.xml`](../../pom.xml)
- [`src/main/java/com/educore/security/JwtService.java`](../../src/main/java/com/educore/security/JwtService.java)
- [`src/test/java/com/educore/security/JwtServiceTest.java`](../../src/test/java/com/educore/security/JwtServiceTest.java)
