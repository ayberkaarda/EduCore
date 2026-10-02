package com.educore.webhook;

import java.time.Instant;
import java.util.List;

/** A webhook subscription as returned by the admin API (never the secret). */
public record WebhookResponse(Long id, String url, List<String> events, boolean active, Long createdBy,
                              Instant createdAt, Instant updatedAt, long droppedEvents) {

    static WebhookResponse of(WebhookSubscription subscription) {
        return new WebhookResponse(subscription.getId(), subscription.getUrl(), List.copyOf(subscription.getEvents()),
                subscription.isActive(), subscription.getCreatedBy(), subscription.getCreatedAt(),
                subscription.getUpdatedAt(), subscription.getDroppedEvents());
    }
}
