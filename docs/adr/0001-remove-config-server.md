# 0001. Remove the Config Server and use environment-first configuration

- Status: Accepted
- Date: 2026-09-25
- Original decision: D-01

## Context

EduCore is a single Spring Boot application. Earlier versions loaded their settings from a separate Spring
Cloud Config Server (`config-server/`, `config-repo/`) through `spring.config.import`, which added a second
service, a second network endpoint and a second place where secrets lived. One application does not need
centralised, multi-service configuration.

## Decision

The Config Server is removed. Both directories are deleted, `spring-cloud-starter-config` and
`spring.config.import` are removed from the build and configuration, and the `config-server` service and the
`CONFIG_SERVER_URL` variable are dropped from `docker-compose.yml`, `.env.example` and the READMEs.

Configuration is environment-first: `application.yml` plus `application-{dev,test,prod,json-logs}.yml`, every
secret supplied as an environment variable documented in `.env.example`, and the
`@ConfigurationProperties("educore")` record tree (`EduCoreProperties`) validated at startup.

## Consequences

Positive:

- One service less to run, patch and secure; no configuration endpoint that could leak secrets.
- Settings are visible in one place (the YAML files and `.env.example`) and fail fast when invalid.

Negative:

- Changing configuration requires a restart with a new environment; there is no runtime refresh.
- Running several differently configured deployments relies on environment files rather than a shared
  configuration repository.

Revert: restore the two directories from version control and re-add `spring-cloud-starter-config` and
`spring.config.import`.

## References

- [`pom.xml`](../../pom.xml)
- [`src/main/resources/application.yml`](../../src/main/resources/application.yml)
- [`src/main/java/com/educore/config/EduCoreProperties.java`](../../src/main/java/com/educore/config/EduCoreProperties.java)
- [`.env.example`](../../.env.example)
- [`docker-compose.yml`](../../docker-compose.yml)
- [`docs/DECISIONS_TAKEN.md`](../DECISIONS_TAKEN.md) (D-01)
