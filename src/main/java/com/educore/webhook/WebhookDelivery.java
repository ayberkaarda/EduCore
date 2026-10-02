package com.educore.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One event for one subscription ({@code webhook_delivery}). {@code id} is the {@code X-EduCore-Delivery}
 * header; {@code payload} is the JSON envelope that is signed and sent as the request body.
 * <ul>
 *   <li>{@code PENDING}: due at {@code nextAttemptAt} (first attempt or a retry);</li>
 *   <li>{@code DELIVERED}: the endpoint answered 2xx;</li>
 *   <li>{@code FAILED}: every attempt failed, or the subscription is gone or inactive.</li>
 * </ul>
 */
@Entity
@Table(name = "webhook_delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookDelivery {

    public enum Status { PENDING, DELIVERED, FAILED }

    static final int MAX_ERROR = 255;

    @Id
    private UUID id;

    @Column(nullable = false)
    private Long subscriptionId;

    @Column(nullable = false, length = 64)
    private String event;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    private Instant nextAttemptAt;

    private Integer responseCode;

    @Column(length = MAX_ERROR)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant deliveredAt;

    WebhookDelivery(UUID id, long subscriptionId, WebhookEvent event, String payload, Instant now) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.event = event.value();
        this.payload = payload;
        this.attempt = 0;
        this.status = Status.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }
}
