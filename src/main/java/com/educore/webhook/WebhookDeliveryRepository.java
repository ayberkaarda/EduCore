package com.educore.webhook;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Page<WebhookDelivery> findBySubscriptionId(Long subscriptionId, Pageable pageable);

    long countBySubscriptionIdAndStatus(Long subscriptionId, WebhookDelivery.Status status);

    /** Retention: deletes DELIVERED and FAILED deliveries created before {@code cutoff}. */
    @Modifying
    @Query("DELETE FROM WebhookDelivery d WHERE d.status <> com.educore.webhook.WebhookDelivery.Status.PENDING "
            + "AND d.createdAt < :cutoff")
    int deleteFinishedBefore(@Param("cutoff") Instant cutoff);
}
