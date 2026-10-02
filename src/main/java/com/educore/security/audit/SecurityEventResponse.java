package com.educore.security.audit;

import java.time.Instant;
import java.util.Map;

/**
 * One audit trail row as returned by {@code GET /api/v1/admin/security-events}. {@code actorPseudonym} /
 * {@code targetPseudonym} ({@code purged:<16 hex>}) replace the account id of an account that was purged.
 */
public record SecurityEventResponse(Long id, SecurityEventType type, Long actorAccountId, Long targetAccountId,
                                    String actorPseudonym, String targetPseudonym, String ip, String requestId,
                                    Instant at, Map<String, Object> details) {

    static SecurityEventResponse of(SecurityEvent event) {
        return new SecurityEventResponse(event.getId(), event.getType(), event.getActorAccountId(),
                event.getTargetAccountId(), event.getActorPseudonym(), event.getTargetPseudonym(), event.getIp(),
                event.getRequestId(), event.getAt(), event.getDetails());
    }
}
