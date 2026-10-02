# 0031. Self-service personal data export

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-72

## Context

Users have a right of access and data portability (KVKK Article 11, GDPR Articles 15 and 20). The export must
not leak secrets, internal flags or other people's data, and must not become a cheap way to load the server.

## Decision

- `GET /api/v1/me/export` returns one JSON attachment in the format `educore.account-export.v1`: profile
  including the assigned IP address, enrollments, and the caller's own security events, with an IP address only
  where the account itself acted. No hashes, tokens, request ids, event details or flags are included.
- The response carries `Cache-Control: no-store`.
- Each account may export once per minute (`educore.lifecycle.export-per-minute`), using the named bucket
  `NamedRateLimits.DATA_EXPORT` of the existing rate-limit store (429 `rate-limit/exceeded` with `Retry-After`).
- Every export writes a `DATA_EXPORTED` audit event.

## Consequences

Positive:

- Reuses the bounded rate-limit store instead of adding a second limiter; the per-account key keeps one user
  from blocking another.

Negative:

- The export is synchronous and capped (`export-max-security-events`, 10 000 newest events); very old events
  beyond the cap are not included.

## References

- [`src/main/java/com/educore/lifecycle/DataExportService.java`](../../src/main/java/com/educore/lifecycle/DataExportService.java)
- [`src/main/java/com/educore/lifecycle/AccountExport.java`](../../src/main/java/com/educore/lifecycle/AccountExport.java)
- [`src/main/java/com/educore/ratelimit/NamedRateLimits.java`](../../src/main/java/com/educore/ratelimit/NamedRateLimits.java)
- [`src/main/java/com/educore/account/MeController.java`](../../src/main/java/com/educore/account/MeController.java)
- [`src/test/java/com/educore/lifecycle/DataExportIT.java`](../../src/test/java/com/educore/lifecycle/DataExportIT.java)
- [`docs/ops/DATA_RETENTION.md`](../ops/DATA_RETENTION.md)
