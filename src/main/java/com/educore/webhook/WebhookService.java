package com.educore.webhook;

import com.educore.common.web.ApiProblemException;
import com.educore.config.EduCoreProperties;
import com.educore.weather.PerUserRateLimiter;
import com.educore.weather.RateLimitedException;
import com.educore.common.web.PageResponse;
import com.educore.common.web.Paging;
import com.educore.security.AuthenticatedUser;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook subscriptions (ADMIN only). Every change writes a {@code WEBHOOK_CHANGED} event
 * ({@code {action, webhookId}}); "send test event" writes {@code WEBHOOK_TEST_REQUESTED}. The signing secret
 * is {@code whsec_} + 64 hex characters (32 random bytes), generated here, returned once and stored encrypted.
 * At most {@code max-subscriptions} subscriptions exist (409 {@code webhook/limit-reached}); test events are
 * limited to {@code test-events-per-minute} per ADMIN (429 {@code webhook/too-many-test-events}) and refused
 * while the pending queue is full (409 {@code webhook/queue-full}).
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class WebhookService {

    static final String NOT_FOUND = "webhook/not-found";
    static final String SECRET_PREFIX = "whsec_";
    static final String LIMIT_REACHED = "webhook/limit-reached";
    static final String QUEUE_FULL = "webhook/queue-full";
    static final String TEST_RATE_LIMITED = "webhook/too-many-test-events";

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookPublisher publisher;
    private final WebhookAddressPolicy addressPolicy;
    private final SecretCipher cipher;
    private final AuditService auditService;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final int maxSubscriptions;
    private final PerUserRateLimiter testEvents;

    public WebhookService(WebhookSubscriptionRepository subscriptions, WebhookDeliveryRepository deliveries,
                          WebhookPublisher publisher, WebhookAddressPolicy addressPolicy, SecretCipher cipher,
                          AuditService auditService, Clock clock, EduCoreProperties properties) {
        this.maxSubscriptions = properties.webhook().maxSubscriptions();
        this.testEvents = new PerUserRateLimiter(properties.webhook().testEventsPerMinute(), Duration.ofMinutes(1));
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.publisher = publisher;
        this.addressPolicy = addressPolicy;
        this.cipher = cipher;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WebhookResponse> list() {
        return subscriptions.findAllByOrderByIdAsc().stream().map(WebhookResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public WebhookResponse get(long id) {
        return WebhookResponse.of(find(id));
    }

    @Transactional
    public WebhookCreatedResponse create(WebhookRequest request) {
        String url = WebhookUrls.validate(request.url(), addressPolicy).toString();
        if (subscriptions.count() >= maxSubscriptions) {
            throw ApiProblemException.conflict(LIMIT_REACHED, "The maximum number of webhook subscriptions exists.");
        }
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String secret = SECRET_PREFIX + HexFormat.of().formatHex(secretBytes);
        WebhookSubscription saved = subscriptions.save(new WebhookSubscription(url, distinct(request.events()),
                cipher.encrypt(secret), request.activeOrDefault(), currentAccountId(), clock.instant()));
        audit(SecurityEventType.WEBHOOK_CHANGED, "CREATED", saved.getId());
        return new WebhookCreatedResponse(WebhookResponse.of(saved), secret);
    }

    @Transactional
    public WebhookResponse update(long id, WebhookRequest request) {
        String url = WebhookUrls.validate(request.url(), addressPolicy).toString();
        WebhookSubscription subscription = find(id);
        subscription.update(url, distinct(request.events()), request.activeOrDefault(), clock.instant());
        audit(SecurityEventType.WEBHOOK_CHANGED, "UPDATED", id);
        return WebhookResponse.of(subscriptions.saveAndFlush(subscription));
    }

    /** Deletes the subscription and its deliveries. */
    @Transactional
    public void delete(long id) {
        subscriptions.delete(find(id));
        subscriptions.flush();
        audit(SecurityEventType.WEBHOOK_CHANGED, "DELETED", id);
    }

    /** Queues a {@code webhook.test} delivery to this subscription (active or not). */
    @Transactional
    public TestDeliveryResponse sendTest(long id) {
        WebhookSubscription subscription = find(id);
        Long caller = currentAccountId();
        testEvents.tryAcquire(caller == null ? "unknown" : caller).ifPresent(wait -> {
            throw new RateLimitedException(TEST_RATE_LIMITED, "Too many test events; try again later.", wait);
        });
        if (!publisher.hasCapacity(subscription.getId())) {
            throw ApiProblemException.conflict(QUEUE_FULL, "The webhook has too many pending deliveries.");
        }
        Instant now = clock.instant();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("webhookId", subscription.getId());
        data.put("message", "Test event sent from the EduCore admin API.");
        UUID deliveryId = publisher.enqueue(subscription.getId(), WebhookEvent.TEST, data, now);
        audit(SecurityEventType.WEBHOOK_TEST_REQUESTED, "TEST", id);
        return new TestDeliveryResponse(deliveryId);
    }

    /** Deliveries of one subscription, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<WebhookDeliveryResponse> deliveries(long id, int page, int size) {
        find(id);
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.of(deliveries.findBySubscriptionId(id, Paging.of(page, size, newestFirst))
                .map(WebhookDeliveryResponse::of));
    }

    private WebhookSubscription find(long id) {
        return subscriptions.findById(id)
                .orElseThrow(() -> ApiProblemException.notFound(NOT_FOUND, "Webhook not found."));
    }

    private static List<String> distinct(List<String> events) {
        return events.stream().distinct().toList();
    }

    private void audit(SecurityEventType type, String action, long webhookId) {
        auditService.recordAction(type, null, Map.of("action", action, "webhookId", webhookId));
    }

    private static Long currentAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? user.id() : null;
    }
}
