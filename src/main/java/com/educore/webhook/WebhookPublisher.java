package com.educore.webhook;

import com.educore.config.EduCoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Queues an event for every active subscription that subscribed to it. Each delivery gets its own id and
 * the JSON envelope {@code {"id", "event", "createdAt", "data"}}; the dispatcher sends it later. {@code data}
 * must hold ids, enum values and counts only (no personal data).
 */
@Service
public class WebhookPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebhookPublisher.class);

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryRepository deliveries;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxPending;

    public WebhookPublisher(WebhookSubscriptionRepository subscriptions, WebhookDeliveryRepository deliveries,
                            ObjectMapper objectMapper, Clock clock, EduCoreProperties properties) {
        this.maxPending = properties.webhook().maxPendingPerSubscription();
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Queues {@code event} for every active subscriber whose pending queue is not full; for a full queue the
     * event is dropped and counted in {@code webhook_subscription.dropped_events}.
     *
     * @return the number of deliveries queued
     */
    @Transactional
    public int publish(WebhookEvent event, Map<String, Object> data) {
        List<WebhookSubscription> targets = subscriptions.findByActiveTrue().stream()
                .filter(subscription -> subscription.subscribedTo(event)).toList();
        Instant now = clock.instant();
        int queued = 0;
        for (WebhookSubscription subscription : targets) {
            if (hasCapacity(subscription.getId())) {
                enqueue(subscription.getId(), event, data, now);
                queued++;
            } else {
                subscriptions.countDroppedEvent(subscription.getId());
                log.warn("Webhook event dropped: pending queue full subscriptionId={} event={}", subscription.getId(),
                        event.value());
            }
        }
        return queued;
    }

    /** Whether the subscription has fewer than {@code max-pending-per-subscription} PENDING deliveries. */
    @Transactional(readOnly = true)
    public boolean hasCapacity(long subscriptionId) {
        return deliveries.countBySubscriptionIdAndStatus(subscriptionId, WebhookDelivery.Status.PENDING) < maxPending;
    }

    /** Queues one delivery for one subscription regardless of its event list (used by "send test event"). */
    @Transactional
    public UUID enqueue(long subscriptionId, WebhookEvent event, Map<String, Object> data, Instant now) {
        UUID id = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", id.toString());
        envelope.put("event", event.value());
        envelope.put("createdAt", now.toString());
        envelope.put("data", data);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Webhook payload could not be serialised", e);
        }
        deliveries.save(new WebhookDelivery(id, subscriptionId, event, payload, now));
        return id;
    }
}
