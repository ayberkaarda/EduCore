package com.educore.webhook;

import java.util.UUID;

/** Answer of {@code POST /api/v1/admin/webhooks/{webhookId}/test}: the queued delivery. */
public record TestDeliveryResponse(UUID deliveryId) {
}
