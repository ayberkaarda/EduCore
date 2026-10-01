package com.educore.security.audit;

import java.time.Instant;
import java.util.Map;

/** One audit trail row as returned by {@code GET /api/v1/admin/security-events}. */
public record SecurityEventResponse(Long id, SecurityEventType type, Long actorAccountId, Long targetAccountId,
                                    String ip, String requestId, Instant at, Map<String, Object> details) {

    static SecurityEventResponse of(SecurityEvent event) {
        return new SecurityEventResponse(event.getId(), event.getType(), event.getActorAccountId(),
                event.getTargetAccountId(), event.getIp(), event.getRequestId(), event.getAt(), event.getDetails());
    }
}
