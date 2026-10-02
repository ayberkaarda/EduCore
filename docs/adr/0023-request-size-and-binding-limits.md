# 0023. Request size limits, strict JSON binding and problem-shaped CORS rejections

- Status: Accepted
- Date: 2026-10-02
- Original decisions: D-NEW-18, D-NEW-20

## Context

The input and output controls had several bypasses: anonymous endpoints could be made to buffer
unbounded bodies; Jackson coerced values (for example `{"role":0}` bound to the first enum constant, `ADMIN`);
`page × size` could overflow an `int` offset; CORS rejections were plain text; Unicode line and format
characters slipped through single-line text checks; Unicode variants of spreadsheet formula triggers escaped
CSV sanitising; and async dispatches could lose the correlation id. Container limits were at framework defaults.

## Decision

- `RequestBodyLimitFilter` (servlet filter ordered right after `RequestIdFilter`, before Spring Security) caps
  non-multipart bodies at `educore.http.max-body-size` (default 64 KB) by `Content-Length` and by a counting
  stream for chunked bodies: 413 `request/payload-too-large`.
- `StrictJsonConfig`: `FAIL_ON_NUMBERS_FOR_ENUMS`, `FAIL_ON_TRAILING_TOKENS`, `FAIL_ON_NULL_FOR_PRIMITIVES`, no
  float-to-int and no string/number/boolean cross-coercion.
- `page × size` must fit an `int`.
- CORS: the default configurer is disabled and one `CorsFilter` per security chain uses `ProblemCorsProcessor`,
  so rejections are 403 `request/cors-rejected` problems; async dispatches are permitted by URL rules because the
  original dispatch was already authorised.
- Single-line text rejects Unicode categories Cc, Cf, Zl, Zp and Cs; CSV formula triggers include fullwidth and
  small-form variants and U+2212.
- `CorrelationIds` reads the id from the request attribute `com.educore.requestId`, then the `X-Request-Id`
  response header, then the MDC.
- Container limits in `application.yml`: multipart file 5 MB and request 6 MB, request headers 16 KB, form posts
  1 MB, swallow size 2 MB; ingestion `max-bytes` 20 MB and `max-rows` 50 000.

## Consequences

Positive:

- Each listed bypass is closed; memory per request is bounded before authentication.
- 16 KB headers still leave room for a bearer token, cookies and forwarding headers.

Negative:

- Clients that relied on lenient JSON coercion receive 400 responses.
- Body limits must be raised deliberately for any future endpoint that needs larger payloads.

## References

- [`src/main/java/com/educore/common/web/RequestBodyLimitFilter.java`](../../src/main/java/com/educore/common/web/RequestBodyLimitFilter.java)
- [`src/main/java/com/educore/common/web/StrictJsonConfig.java`](../../src/main/java/com/educore/common/web/StrictJsonConfig.java)
- [`src/main/java/com/educore/security/ProblemCorsProcessor.java`](../../src/main/java/com/educore/security/ProblemCorsProcessor.java)
- [`src/main/java/com/educore/common/web/CorrelationIds.java`](../../src/main/java/com/educore/common/web/CorrelationIds.java)
- [`src/main/java/com/educore/common/text/CsvSanitizer.java`](../../src/main/java/com/educore/common/text/CsvSanitizer.java)
- [`src/main/resources/application.yml`](../../src/main/resources/application.yml)
- [`src/test/java/com/educore/security/CorsIT.java`](../../src/test/java/com/educore/security/CorsIT.java)
