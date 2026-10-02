package com.educore.webhook;

import com.educore.config.EduCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Sends due webhook deliveries ({@code @Scheduled}, every {@code educore.webhook.dispatch-interval}).
 * <p>
 * Deliveries are claimed one at a time, immediately before they are sent: the claim
 * ({@code FOR UPDATE SKIP LOCKED}, so instances never claim the same row) stores a fresh {@code claim_token}
 * and pushes {@code next_attempt_at} forward by a short lease ({@code request-deadline} + 30 s), which makes a
 * row whose sender died due again. Every result update is fenced by the token: once a lease expired and
 * another dispatcher re-claimed the row, a late result of the first sender changes nothing.
 * <p>
 * Each request carries {@code X-EduCore-Event}, {@code X-EduCore-Delivery}, {@code X-EduCore-Timestamp} and
 * {@code X-EduCore-Signature} ({@link WebhookSigner}). A 2xx answer marks the delivery DELIVERED; anything else
 * (3xx included, redirects are not followed) is a failed attempt, retried after {@link #backoff} until
 * {@code max-retries} retries are used up, then FAILED.
 */
@Component
public class WebhookDispatcher {

    static final Duration LEASE_MARGIN = Duration.ofSeconds(30);
    static final double MAX_JITTER = 0.2;

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private static final String CLAIM_SQL = """
            UPDATE webhook_delivery SET next_attempt_at = ?, claim_token = ?
            WHERE id = (SELECT id FROM webhook_delivery
                        WHERE status = 'PENDING' AND next_attempt_at <= ?
                        ORDER BY next_attempt_at
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED)
            RETURNING id, attempt""";

    private static final String DELIVERED_SQL = """
            UPDATE webhook_delivery SET status = 'DELIVERED', attempt = attempt + 1, response_code = ?,
                last_error = NULL, next_attempt_at = NULL, delivered_at = ?, claim_token = NULL
            WHERE id = ? AND claim_token = ?""";

    private static final String FAILED_ATTEMPT_SQL = """
            UPDATE webhook_delivery SET status = ?, attempt = attempt + 1, response_code = ?, last_error = ?,
                next_attempt_at = ?, claim_token = NULL
            WHERE id = ? AND claim_token = ?""";

    private static final String ABANDON_SQL = """
            UPDATE webhook_delivery SET status = 'FAILED', last_error = ?, next_attempt_at = NULL, claim_token = NULL
            WHERE id = ? AND claim_token = ?""";

    /** A claimed delivery: its id, the attempts made so far and the fencing token. */
    record Claim(UUID id, int attempt, UUID token) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookSubscriptionRepository subscriptions;
    private final SecretCipher cipher;
    private final WebhookTransport transport;
    private final EduCoreProperties.Webhook settings;
    private final Clock clock;
    private final RandomGenerator random = new SecureRandom();

    public WebhookDispatcher(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                             WebhookDeliveryRepository deliveries, WebhookSubscriptionRepository subscriptions,
                             SecretCipher cipher, WebhookTransport transport, EduCoreProperties properties,
                             Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.cipher = cipher;
        this.transport = transport;
        this.settings = properties.webhook();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${educore.webhook.dispatch-interval:5s}",
            initialDelayString = "${educore.webhook.dispatch-interval:5s}")
    public void scheduledRun() {
        if (settings.dispatcherEnabled()) {
            dispatchDue();
        }
    }

    /** Sends deliveries that are due, one claim at a time (at most {@code batch-size}); returns how many. */
    public int dispatchDue() {
        int sent = 0;
        while (sent < settings.batchSize()) {
            Optional<Claim> claim = claimNext();
            if (claim.isEmpty()) {
                break;
            }
            sent++;
            try {
                deliver(claim.get());
            } catch (RuntimeException e) {
                log.error("Webhook delivery could not be processed deliveryId={} error={}", claim.get().id(),
                        e.getClass().getName());
            }
        }
        return sent;
    }

    /** Claims the next due delivery with a fresh token and a lease of {@code request-deadline} + 30 s. */
    Optional<Claim> claimNext() {
        Instant now = clock.instant();
        UUID token = UUID.randomUUID();
        Object[] arguments = {utc(now.plus(settings.requestDeadline()).plus(LEASE_MARGIN)), token, utc(now)};
        RowMapper<Claim> mapper = (rs, row) -> new Claim(rs.getObject("id", UUID.class), rs.getInt("attempt"), token);
        List<Claim> claimed = transactions.execute(status -> jdbc.query(CLAIM_SQL, mapper, arguments));
        return claimed == null ? Optional.empty() : claimed.stream().findFirst();
    }

    private void deliver(Claim claim) {
        WebhookDelivery delivery = deliveries.findById(claim.id()).orElse(null);
        if (delivery == null) {
            return;
        }
        Optional<WebhookSubscription> subscription = subscriptions.findById(delivery.getSubscriptionId());
        boolean test = WebhookEvent.TEST.value().equals(delivery.getEvent());
        if (subscription.isEmpty() || (!subscription.get().isActive() && !test)) {
            abandon(claim, "subscription-inactive");
            return;
        }
        String secret;
        try {
            secret = cipher.decrypt(subscription.get().getSecretEncrypted());
        } catch (IllegalStateException e) {
            failAttempt(claim, null, "secret-unavailable");
            return;
        }
        byte[] body = delivery.getPayload().getBytes(StandardCharsets.UTF_8);
        long timestamp = clock.instant().getEpochSecond();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-EduCore-Event", delivery.getEvent());
        headers.put("X-EduCore-Delivery", claim.id().toString());
        headers.put("X-EduCore-Timestamp", Long.toString(timestamp));
        headers.put("X-EduCore-Signature", WebhookSigner.sign(secret, timestamp, body));
        int status;
        try {
            status = transport.send(URI.create(subscription.get().getUrl()), headers, body);
        } catch (WebhookTransport.WebhookSendException e) {
            log.warn("Webhook delivery failed deliveryId={} subscriptionId={} attempt={} error={}", claim.id(),
                    delivery.getSubscriptionId(), claim.attempt() + 1, e.getMessage());
            failAttempt(claim, null, e.getMessage());
            return;
        }
        if (status >= 200 && status < 300) {
            if (delivered(claim, status)) {
                log.info("Webhook delivered deliveryId={} subscriptionId={} status={}", claim.id(),
                        delivery.getSubscriptionId(), status);
            }
        } else {
            log.warn("Webhook delivery rejected deliveryId={} subscriptionId={} attempt={} status={}", claim.id(),
                    delivery.getSubscriptionId(), claim.attempt() + 1, status);
            failAttempt(claim, status, status >= 300 && status < 400 ? "redirect-not-followed" : "http-" + status);
        }
    }

    /** @return {@code false} when the claim lost its lease to another dispatcher (nothing changed) */
    boolean delivered(Claim claim, int responseCode) {
        Object[] arguments = {responseCode, utc(clock.instant()), claim.id(), claim.token()};
        return fenced(transactions.execute(status -> jdbc.update(DELIVERED_SQL, arguments)), claim);
    }

    /** @return {@code false} when the claim lost its lease to another dispatcher (nothing changed) */
    boolean failAttempt(Claim claim, Integer responseCode, String error) {
        int attemptNumber = claim.attempt() + 1;
        Instant retryAt = attemptNumber > settings.maxRetries() ? null
                : clock.instant().plus(backoff(attemptNumber, settings.initialBackoff(), settings.maxBackoff(),
                random.nextDouble()));
        String status = retryAt == null ? WebhookDelivery.Status.FAILED.name() : WebhookDelivery.Status.PENDING.name();
        String lastError = error == null || error.length() <= WebhookDelivery.MAX_ERROR ? error
                : error.substring(0, WebhookDelivery.MAX_ERROR);
        Object[] arguments = {status, responseCode, lastError, retryAt == null ? null : utc(retryAt), claim.id(),
                claim.token()};
        return fenced(transactions.execute(tx -> jdbc.update(FAILED_ATTEMPT_SQL, arguments)), claim);
    }

    private boolean abandon(Claim claim, String error) {
        Object[] arguments = {error, claim.id(), claim.token()};
        return fenced(transactions.execute(status -> jdbc.update(ABANDON_SQL, arguments)), claim);
    }

    private static boolean fenced(Integer updated, Claim claim) {
        if (updated == null || updated == 0) {
            log.warn("Webhook delivery result ignored: lease taken over deliveryId={}", claim.id());
            return false;
        }
        return true;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Delay before retry {@code retry} (1-based): {@code initial * 2^(retry-1)}, capped at {@code max}, plus
     * {@code jitterFraction * 20 %} of that delay ({@code jitterFraction} in {@code [0, 1)}).
     */
    static Duration backoff(int retry, Duration initial, Duration max, double jitterFraction) {
        long base = initial.toMillis();
        for (int i = 1; i < retry && base < max.toMillis(); i++) {
            base *= 2;
        }
        base = Math.min(base, max.toMillis());
        long jitter = (long) (base * MAX_JITTER * jitterFraction);
        return Duration.ofMillis(base + jitter);
    }
}
