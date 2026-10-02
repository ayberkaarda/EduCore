package com.educore.webhook;

import com.educore.config.EduCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes DELIVERED and FAILED webhook deliveries older than {@code educore.webhook.delivery-retention}
 * (14 days), every {@code educore.webhook.retention-interval} (1 h). PENDING deliveries are never deleted.
 */
@Component
public class WebhookRetention {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetention.class);

    private final WebhookDeliveryRepository deliveries;
    private final Duration retention;
    private final Clock clock;

    public WebhookRetention(WebhookDeliveryRepository deliveries, EduCoreProperties properties, Clock clock) {
        this.deliveries = deliveries;
        this.retention = properties.webhook().deliveryRetention();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${educore.webhook.retention-interval:1h}",
            initialDelayString = "${educore.webhook.retention-interval:1h}")
    @Transactional
    public int purge() {
        int deleted = deliveries.deleteFinishedBefore(clock.instant().minus(retention));
        if (deleted > 0) {
            log.info("Expired webhook deliveries deleted count={}", deleted);
        }
        return deleted;
    }
}
