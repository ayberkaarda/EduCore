# 0018. One audit writer for the security event trail

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-11

## Context

Authentication events were written by `SecurityEventRecorder`, while administrative mutations needed their own
audit records with the acting account and client IP. Two writers for one table would drift apart. Recording
every self-service action as an audit event would also bury administrative changes in ordinary user activity.

## Decision

- `com.educore.security.audit.AuditService` replaces `SecurityEventRecorder`. It writes the same
  `security_event` table, keeps the API for authentication events and adds `recordAction`, which takes the
  actor and client IP from the current request.
- Enrollment changes are audited only when the actor is not the account owner; requests that change nothing
  write no event.

## Consequences

Positive:

- One code path and one shape for every audit record; ADMIN mutations are always attributable.
- The trail is not flooded by self-service enrollment.

Negative:

- A user's own enrollment history is not in the audit trail (it is visible in the enrollment data itself).

## References

- [`src/main/java/com/educore/security/audit/AuditService.java`](../../src/main/java/com/educore/security/audit/AuditService.java)
- [`src/main/java/com/educore/security/audit/SecurityEventType.java`](../../src/main/java/com/educore/security/audit/SecurityEventType.java)
- [`src/main/java/com/educore/enrollment/EnrollmentService.java`](../../src/main/java/com/educore/enrollment/EnrollmentService.java)
- [`src/main/resources/db/migration/V10__auth_tokens.sql`](../../src/main/resources/db/migration/V10__auth_tokens.sql)
- [`src/test/java/com/educore/authz/AuditEventIT.java`](../../src/test/java/com/educore/authz/AuditEventIT.java)
