package com.educore.webhook;

/**
 * Answer of {@code POST /api/v1/admin/webhooks}: the subscription and its signing secret. The secret is
 * returned only here; it is stored encrypted and cannot be read again.
 */
public record WebhookCreatedResponse(WebhookResponse webhook, String secret) {
}
