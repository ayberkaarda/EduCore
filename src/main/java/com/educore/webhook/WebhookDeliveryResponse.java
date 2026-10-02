package com.educore.webhook;

import java.time.Instant;
import java.util.UUID;

/** One delivery of a subscription as returned by the admin API (without the payload). */
public record WebhookDeliveryResponse(UUID id, String event, int attempt, WebhookDelivery.Status status,
                                      Instant nextAttemptAt, Integer responseCode, String lastError,
                                      Instant createdAt, Instant deliveredAt) {

    static WebhookDeliveryResponse of(WebhookDelivery delivery) {
        return new WebhookDeliveryResponse(delivery.getId(), delivery.getEvent(), delivery.getAttempt(),
                delivery.getStatus(), delivery.getNextAttemptAt(), delivery.getResponseCode(), delivery.getLastError(),
                delivery.getCreatedAt(), delivery.getDeliveredAt());
    }
}
