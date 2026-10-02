package com.educore.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A webhook endpoint ({@code webhook_subscription}): an https URL, the subscribed event names and the signing
 * secret encrypted with {@link SecretCipher}. The plaintext secret is shown once, at creation.
 */
@Entity
@Table(name = "webhook_subscription")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2048)
    private String url;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> events = new ArrayList<>();

    @Column(nullable = false, length = 512)
    private String secretEncrypted;

    @Column(nullable = false)
    private boolean active;

    private Long createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Events not queued because the subscription's pending queue was full. */
    @Column(nullable = false, insertable = false, updatable = false)
    private long droppedEvents;

    WebhookSubscription(String url, List<String> events, String secretEncrypted, boolean active, Long createdBy,
                        Instant now) {
        this.url = url;
        this.events = new ArrayList<>(events);
        this.secretEncrypted = secretEncrypted;
        this.active = active;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String url, List<String> events, boolean active, Instant now) {
        this.url = url;
        this.events = new ArrayList<>(events);
        this.active = active;
        this.updatedAt = now;
    }

    boolean subscribedTo(WebhookEvent event) {
        return events.contains(event.value());
    }
}
