# Logging

What the backend writes to its logs, in which format, and what it never writes. Checklist item S14
("logları temizle"); the request limits at the end belong to S7.

## Format

| Profile | Console format | Selected by |
|---|---|---|
| `prod` | One JSON object per line, [Elastic Common Schema](https://www.elastic.co/guide/en/ecs/current/) (`logging.structured.format.console: ecs`) | `logback-spring.xml` (`<springProfile name="prod \| json-logs">`) |
| `json-logs` (optional, e.g. `SPRING_PROFILES_ACTIVE=dev,json-logs`) | Same JSON as prod | same |
| `dev`, `test` | Spring Boot's human-readable pattern with a `[requestId,userId]` prefix (`logging.pattern.correlation`) | `logback-spring.xml` |

**Why ECS and not `logstash`.** Both are built into Spring Boot 3.5. ECS is a published, versioned schema
(`ecs.version`) that Elastic/OpenSearch, Grafana Loki/Alloy and Datadog map without custom parsing rules; it
separates `error.type`, `error.message` and `error.stack_trace`, and names the service (`service.name` from
`spring.application.name`). The logstash layout has no schema of its own. Switching is one property:
`logging.structured.format.console: logstash` (the masking customizer works with either).

Typical prod line (wrapped here):

```json
{"@timestamp":"2026-10-01T10:15:30.123Z","log":{"level":"INFO","logger":"com.educore.security.audit.AuditService"},
 "process":{"pid":1,"thread":{"name":"http-nio-8080-exec-3"}},"service":{"name":"educore","node":{}},
 "message":"SECURITY_EVENT type=PASSWORD_CHANGED actor=12 target=12 requestId=7d3c...","requestId":"7d3c...",
 "userId":"12","ecs":{"version":"8.11"}}
```

Stack traces in JSON: root cause first, at most 8192 characters, common frames folded
(`logging.structured.json.stacktrace.*`).

## Correlation (MDC)

| Key | Set by | Value | Cleared |
|---|---|---|---|
| `requestId` | `security/RequestIdFilter` (first filter, also on the error dispatch) | Incoming `X-Request-Id` if it is 1 to 64 characters of `[A-Za-z0-9._-]`; otherwise (missing, longer, CR/LF, any other character) a new UUID. Returned in the `X-Request-Id` response header, stored in the request attribute `com.educore.requestId` and in every `security_event` row; 5xx problems carry it as `correlationId`. | `finally` of the filter |
| `userId` | `security/JwtAuthenticationFilter` after a bearer token was accepted | The account id (never the username or token) | `finally` of the filter |

Constants: `common/logging/MdcKeys`.

## Masking (`common/logging/PiiMasking`)

Applied at output time to every message (with its arguments substituted) and every stack trace:
`PiiMaskingConverter` (`%m`/`%msg`/`%message`) and `PiiMaskingThrowableConverter` (`%wEx`) in the pattern
format, `PiiMaskingJsonCustomizer` (`logging.structured.json.customizer`) for every string member of the JSON
event except `requestId` and `userId`.

| Rule | Example in | Logged as |
|---|---|---|
| Unicode line/paragraph separators (NEL, U+2028, U+2029) and bidi controls (JSON escapes CR/LF but not these) | `eve<U+2028>FORGED` | `eve_FORGED` |
| `rejected value [...]` of a binding error (Spring DEBUG output; can quote a submitted password) | `rejected value [hunter2]` | `rejected value [[REDACTED]]` |
| Secret `key=value` / `"key":"value"` (key ends in password, passwd, pwd, secret, token, authorization, cookie, api-key) | `"newPassword":"..."`, `Cookie: educore_refresh=...` | `"newPassword":"[REDACTED]"`, `Cookie: [REDACTED]` |
| JWT (`eyJ….….…`) | access token | `[REDACTED-JWT]` |
| `Bearer` / `Basic` credentials | `Bearer abc…` | `Bearer [REDACTED]` |
| E-mail | `ayse.yilmaz@example.com` | `a***@example.com` |
| Temporary password shape (24 characters of the generator alphabet with upper, lower, digit, symbol) | issued student password | `[REDACTED]` |
| Opaque token: 32+ URL-safe base64/hex characters mixing letters and digits (refresh tokens, hashes, keys); tokens containing a UUID (request ids, test usernames) are kept | refresh token value | `[REDACTED]` |
| IPv4 | `192.168.1.34` | `192.168.1.***` |
| Student number: labelled 4–12 digits, or any stand-alone run of 6–12 digits | `studentNumber=20230145` | `studentNumber=******45` |

Bare numbers below 6 digits (ports, years, counts, small ids) and digits inside identifiers, dates, UUIDs and
versions are not touched. Masking is a safety net; the rules below keep the data out of log statements in the
first place.

## Rules for log statements

1. **Never log** request or response bodies, `Authorization`/`Cookie`/`Set-Cookie` headers, passwords
   (current, new, temporary, bootstrap), tokens (access, refresh, webhook secrets), the JWT/pepper/encryption
   keys, or exception messages that can quote them (`JwtAuthenticationFilter` logs only the exception type).
   Request DTOs that carry passwords override `toString()` (`LoginRequest`, `PasswordChangeRequest`), so even
   Spring MVC's DEBUG output shows `<omitted>`.
2. **User-controlled values** (usernames, header values, file names, free text) go through
   `LogSanitizer.sanitize(value)`: control characters, Unicode line/paragraph separators, format characters
   (bidi overrides, zero-width) and unpaired surrogates become `_`, and the value is capped at 200 characters
   (`...` suffix). Example: `AuthService` logs `LOGIN_FAILED reason=<reason> username=<sanitised>` for each
   failed login (OWASP: authentication failures are logged with the identifier; the password and client IP
   are not in that line, the IP is in the `security_event` row). Input validation already rejects these
   characters at the HTTP boundary (`InputPatterns.SINGLE_LINE_TEXT`); the sanitiser covers every other caller.
3. Prefer ids and enum values over names: `SECURITY_EVENT type=… actor=<id> target=<id>`.
4. No `System.out`/`System.err`/`printStackTrace()` (enforced by `ArchitectureTest`). Log an exception with
   the logger and its correlation id: `log.error("... (correlationId={})", id, e)`.
5. Hibernate SQL and bind parameters are never logged (`spring.jpa.show-sql=false`,
   `org.hibernate.SQL`/`org.hibernate.orm.jdbc.bind` at WARN). DEBUG on `org.springframework.web` prints
   request DTOs through their `toString()`; it is not enabled in any profile.

## Request limits (configured here, enforced for ingestion in P6)

| Property | Value | Effect |
|---|---|---|
| `spring.servlet.multipart.max-file-size` | 5MB | Larger upload part: 413 `request/payload-too-large` |
| `spring.servlet.multipart.max-request-size` | 6MB | Whole multipart request |
| `educore.ingestion.max-bytes` | 20MB | Per CSV file, however it arrives (`EduCoreProperties.Ingestion` default) |
| `educore.ingestion.max-rows` | 50000 | Data rows per CSV file (same) |
| `server.max-http-request-header-size` | 16KB | Request line plus headers; larger: 400 |
| `server.tomcat.max-http-form-post-size` | 1MB | `application/x-www-form-urlencoded` bodies |
| `server.tomcat.max-swallow-size` | 2MB | Bytes of an aborted upload still read before the connection is closed |

Non-multipart JSON bodies are capped separately by `RequestBodyLimitFilter` (`educore.http.max-body-size`,
see `docs/DECISIONS_TAKEN.md` D-NEW-18).

## Tests

`LogSanitizerTest`, `PiiMaskingTest` (every rule; plain converter, stack trace converter and JSON customizer),
`RequestIdFilterTest`, `JwtAuthenticationFilterMdcTest`, and `LoggingIT`, which runs the application in the
`json-logs` format with Spring Web and Spring Security at DEBUG and checks the captured console output: a login
and a password change never write the passwords, the access token, a foreign bearer token or the refresh
cookie; CR/LF and Unicode line separators in a username are rejected over HTTP and sanitised when they reach
`AuthService`; every line parses as JSON and carries `requestId` (and `userId` once authenticated); unsafe
`X-Request-Id` values are replaced.
