package com.educore.lifecycle;

import com.educore.entity.AccountStatus;
import com.educore.entity.Role;
import com.educore.security.audit.SecurityEventType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Body of {@code GET /api/v1/me/export} (data portability, docs/ops/DATA_RETENTION.md). It holds the data the
 * account owner provided or that describes them, and nothing that would help an attacker or expose other
 * people: no password hash, token, token family, username hash, request id, optimistic-lock version or
 * {@code mustChangePassword} flag, and no IP address of another person.
 *
 * @param format                   {@value #FORMAT}
 * @param securityEvents           the newest {@code educore.lifecycle.export-max-security-events} events the
 *                                 account caused or was the target of, newest first
 * @param securityEventsTruncated  whether older events exist beyond that limit
 */
public record AccountExport(String format, Instant exportedAt, Profile profile, List<Enrollment> enrollments,
                            List<Event> securityEvents, boolean securityEventsTruncated) {

    public static final String FORMAT = "educore.account-export.v1";

    /** The account itself; {@code ipAddress} is the student IP assigned by an ADMIN (or null). */
    public record Profile(Long id, String username, String firstName, String lastName, String studentNumber,
                          Role role, String ipAddress, AccountStatus status) {
    }

    /** One enrollment; {@code enrolledAt} is the server's local date-time of the enrollment. */
    public record Enrollment(Long courseId, String courseName, String term, String instructor,
                             LocalDateTime enrolledAt) {
    }

    /**
     * One audit event. {@code involvement}: {@code ACTOR} (the account acted), {@code TARGET} (another actor or
     * an anonymous client acted on the account) or {@code ACTOR_AND_TARGET}. {@code ip} is present only when the
     * account itself was the actor.
     */
    public record Event(SecurityEventType type, Instant at, String involvement, String ip) {
    }
}
