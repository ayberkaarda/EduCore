# 0024. ECS structured logging in production with PII masking

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-19

## Context

Production logs must be machine-parseable and correlatable, must not contain secrets or personal data, and
should need no custom parsing in common log stacks. Local development logs must stay readable.

## Decision

- `prod` and the optional `json-logs` profile use Spring Boot's built-in structured logging in the ECS format
  (`logging.structured.format.console: ecs`); `dev` and `test` keep a human-readable pattern with a
  `[requestId,userId]` prefix. `logback-spring.xml` selects the appender by profile.
- `logback-spring.xml` replaces `%m`, `%msg`, `%message` and `%wEx` with masking converters; JSON string members
  are masked by `PiiMaskingJsonCustomizer`, except `requestId` and `userId`.
- Masking covers secret key/value pairs, JWTs, Bearer/Basic credentials, the e-mail local part, the temporary
  password shape, long opaque tokens (except UUIDs), the last IPv4 octet and student numbers (keeping the last
  two digits). `LogSanitizer` replaces control, format and separator characters with `_` and truncates to 200
  characters. Details: `docs/security/LOGGING.md`.
- `AuthService` logs one `LOGIN_FAILED reason=... username=<sanitised>` line per failed login; the database keeps
  only the HMAC of the username.
- `RequestIdFilter` also runs on the error dispatch and stores the id in the `com.educore.requestId` request
  attribute.

## Consequences

Positive:

- A versioned public schema, no extra dependency, and the same format locally through `json-logs`.
- Personal data and credentials are masked even when a developer logs them by mistake.

Negative:

- Masking is pattern-based and can over-mask (or miss unusual formats); bare numbers below 6 digits are
  deliberately not masked so ports, years and small ids stay readable.

## References

- [`src/main/resources/logback-spring.xml`](../../src/main/resources/logback-spring.xml)
- [`src/main/resources/application-prod.yml`](../../src/main/resources/application-prod.yml)
- [`src/main/resources/application-json-logs.yml`](../../src/main/resources/application-json-logs.yml)
- [`src/main/java/com/educore/common/logging/PiiMaskingJsonCustomizer.java`](../../src/main/java/com/educore/common/logging/PiiMaskingJsonCustomizer.java)
- [`src/main/java/com/educore/common/logging/LogSanitizer.java`](../../src/main/java/com/educore/common/logging/LogSanitizer.java)
- [`src/main/java/com/educore/security/RequestIdFilter.java`](../../src/main/java/com/educore/security/RequestIdFilter.java)
- [`src/test/java/com/educore/common/logging/LoggingIT.java`](../../src/test/java/com/educore/common/logging/LoggingIT.java)
- [`docs/security/LOGGING.md`](../security/LOGGING.md)
