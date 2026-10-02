package com.educore.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, Long> {

    List<WebhookSubscription> findByActiveTrue();

    List<WebhookSubscription> findAllByOrderByIdAsc();

    @Modifying
    @Query("UPDATE WebhookSubscription s SET s.droppedEvents = s.droppedEvents + 1 WHERE s.id = :id")
    int countDroppedEvent(@Param("id") Long id);
}
