package com.educore.webhook;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code POST/PUT /api/v1/admin/webhooks}: a public https URL, one to four subscribable event names
 * and whether the subscription is active ({@code true} when omitted).
 */
public record WebhookRequest(
        @NotBlank @Size(max = WebhookUrls.MAX_LENGTH) String url,
        @NotEmpty @Size(max = 4) List<@NotNull @Pattern(regexp = WebhookEvent.SUBSCRIBABLE_PATTERN) String> events,
        Boolean active) {

    boolean activeOrDefault() {
        return active == null || active;
    }
}
