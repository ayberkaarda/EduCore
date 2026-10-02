# 0019. RFC 9457 Problem Details as the only error shape

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-12

## Context

Errors were rendered by three scoped handlers (`AuthExceptionHandler`, `ApiExceptionHandler` and the legacy
`exception/GlobalExceptionHandler` with `{"error": ...}`). Security failures used different shapes again: an
empty 401 body and Spring's `{timestamp, status, error, path}` for 403. Clients had to parse several formats,
and exception messages could reach responses.

## Decision

- One `@RestControllerAdvice`, `com.educore.common.web.ProblemDetailsAdvice` (extending
  `ResponseEntityExceptionHandler`), renders every application error as `application/problem+json`.
- `ProblemErrorController` replaces Boot's `BasicErrorController` for container-level errors, and both security
  chains use `ProblemSecurityHandlers` for 401 and 403.
- `AuthProblemException` extends `ApiProblemException`; clearing the refresh cookie on auth problems moved to a
  `ProblemHeaderContributor`.
- Every problem carries a stable `code`, a fixed `detail` and `instance`; 5xx problems carry only a
  `correlationId` equal to `X-Request-Id`. Stack traces and exception messages are never included
  (`server.error.include-*: never`).

## Consequences

Positive:

- One mapping table and one client-side parser for every status and path.
- HTTP statuses were unchanged, so the RBAC matrix still holds.

Negative:

- All error codes are now part of the public contract and must be kept stable.

## References

- [`src/main/java/com/educore/common/web/ProblemDetailsAdvice.java`](../../src/main/java/com/educore/common/web/ProblemDetailsAdvice.java)
- [`src/main/java/com/educore/common/web/ProblemErrorController.java`](../../src/main/java/com/educore/common/web/ProblemErrorController.java)
- [`src/main/java/com/educore/security/ProblemSecurityHandlers.java`](../../src/main/java/com/educore/security/ProblemSecurityHandlers.java)
- [`src/main/java/com/educore/common/web/ProblemHeaderContributor.java`](../../src/main/java/com/educore/common/web/ProblemHeaderContributor.java)
- [`src/test/java/com/educore/web/ProblemDetailsIT.java`](../../src/test/java/com/educore/web/ProblemDetailsIT.java)
