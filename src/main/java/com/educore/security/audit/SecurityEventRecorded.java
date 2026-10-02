package com.educore.security.audit;

/**
 * Published by {@link AuditService} for every recorded event, so features can react to security events
 * (for example the automatic IP deny rule after repeated failed logins) without the recording code knowing
 * about them. Listeners that write data should run after the recording transaction commits.
 *
 * @param ip the client IP of the event, or {@code null}
 */
public record SecurityEventRecorded(SecurityEventType type, String ip) {
}
