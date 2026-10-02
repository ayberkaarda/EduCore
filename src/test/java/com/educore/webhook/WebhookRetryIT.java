package com.educore.webhook;

import com.educore.authz.AuthzIntegrationSupport;
import com.educore.entity.Account;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Real HTTPS deliveries to a local endpoint: signature headers, retries with backoff after 5xx, giving up
 * after the configured retries, no redirect following and read timeouts. The SSRF policy is relaxed for
 * 127.0.0.1 only in this context (the endpoint is local); the TLS trust is the test certificate.
 */
@Import(WebhookRetryIT.LocalEndpointConfig.class)
@DirtiesContext
@TestPropertySource(properties = {
        "educore.webhook.initial-backoff=1s",
        "educore.webhook.max-backoff=1s",
        "educore.webhook.read-timeout=1s",
        "educore.webhook.connect-timeout=1s"})
class WebhookRetryIT extends AuthzIntegrationSupport {

    private static HttpsTestServer server;

    @Autowired
    private WebhookDispatcher dispatcher;

    @TestConfiguration
    static class LocalEndpointConfig {

        @Bean
        @Primary
        WebhookAddressPolicy loopbackAllowingPolicy() {
            return new WebhookAddressPolicy() {
                @Override
                public boolean isAllowed(InetAddress address) {
                    return address.isLoopbackAddress() || super.isAllowed(address);
                }
            };
        }

        @Bean
        @Primary
        WebhookConfig.WebhookTls testCertificateTls() {
            return new WebhookConfig.WebhookTls(HttpsTestServer.clientContext());
        }
    }

    @BeforeAll
    static void startServer() throws Exception {
        server = new HttpsTestServer();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @AfterEach
    void removeSubscriptions() {
        jdbc.update("DELETE FROM webhook_subscription WHERE url LIKE 'https://127.0.0.1:%'");
        server.reset();
    }

    private record Subscription(long id, String secret) {
    }

    private Subscription subscribe(Account admin, String path) throws Exception {
        JsonNode created = body(perform(admin, post("/api/v1/admin/webhooks"),
                map("url", server.url(path), "events", List.of("course.updated"))));
        return new Subscription(created.get("webhook").get("id").asLong(), created.get("secret").asText());
    }

    private UUID sendTest(Account admin, Subscription subscription) throws Exception {
        return UUID.fromString(body(perform(admin, post("/api/v1/admin/webhooks/" + subscription.id() + "/test"), null))
                .get("deliveryId").asText());
    }

    private Map<String, Object> delivery(UUID id) {
        return jdbc.queryForMap("SELECT status, attempt, response_code, last_error, next_attempt_at "
                + "FROM webhook_delivery WHERE id = ?", id);
    }

    /** Runs the dispatcher until the delivery has made {@code attempts} attempts (waiting out the backoff). */
    private void dispatchUntilAttempts(UUID id, int attempts) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (((Number) delivery(id).get("attempt")).intValue() < attempts) {
            assertThat(Instant.now()).as("attempt %s of %s", attempts, id).isBefore(deadline);
            if (dispatcher.dispatchDue() == 0) {
                pause();
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void retriesAfterServerErrorsWithBackoffAndSignsEveryAttempt() throws Exception {
        Account admin = account(Role.ADMIN);
        Subscription subscription = subscribe(admin, "/hook/retry");
        server.reply(HttpsTestServer.Reply.status(500), HttpsTestServer.Reply.status(503));
        UUID id = sendTest(admin, subscription);

        dispatchUntilAttempts(id, 1);
        Map<String, Object> afterFirst = delivery(id);
        assertThat(afterFirst.get("status")).isEqualTo("PENDING");
        assertThat(afterFirst.get("response_code")).isEqualTo(500);
        assertThat(afterFirst.get("last_error")).isEqualTo("http-500");
        Duration wait = Duration.between(Instant.now(), ((java.sql.Timestamp) afterFirst.get("next_attempt_at")).toInstant());
        assertThat(wait).isBetween(Duration.ofMillis(-200), Duration.ofMillis(1300));
        dispatchUntilAttempts(id, 3);

        Map<String, Object> done = delivery(id);
        assertThat(done.get("status")).isEqualTo("DELIVERED");
        assertThat(done.get("attempt")).isEqualTo(3);
        List<HttpsTestServer.Received> requests = server.received();
        assertThat(requests).hasSize(3).allSatisfy(request -> {
            assertThat(request.path()).isEqualTo("/hook/retry");
            assertThat(request.headers()).containsEntry("X-EduCore-Event", "webhook.test")
                    .containsEntry("X-EduCore-Delivery", id.toString())
                    .containsEntry("Content-Type", "application/json; charset=UTF-8");
            String timestamp = request.headers().get("X-EduCore-Timestamp");
            assertThat(Math.abs(Long.parseLong(timestamp) - Instant.now().getEpochSecond())).isLessThan(60);
            assertThat(WebhookSigner.verify(subscription.secret(), timestamp, request.body(),
                    request.headers().get("X-EduCore-Signature"), Instant.now(), WebhookSigner.DEFAULT_TOLERANCE))
                    .isTrue();
            JsonNode envelope = readJson(request.body());
            assertThat(envelope.get("id").asText()).isEqualTo(id.toString());
            assertThat(envelope.get("event").asText()).isEqualTo("webhook.test");
            assertThat(envelope.get("data").get("webhookId").asLong()).isEqualTo(subscription.id());
        });
    }

    @Test
    void givesUpAfterFiveRetries() throws Exception {
        Account admin = account(Role.ADMIN);
        Subscription subscription = subscribe(admin, "/hook/down");
        for (int i = 0; i < 10; i++) {
            server.reply(HttpsTestServer.Reply.status(500));
        }
        UUID id = sendTest(admin, subscription);

        dispatchUntilAttempts(id, 6);

        Map<String, Object> failed = delivery(id);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("attempt")).isEqualTo(6);
        assertThat(failed.get("next_attempt_at")).isNull();
        Instant later = Instant.now().plusSeconds(2);
        while (Instant.now().isBefore(later)) {
            dispatcher.dispatchDue();
            pause();
        }
        assertThat(server.received()).hasSize(6);
    }

    @Test
    void redirectsAreNotFollowedAndSlowEndpointsTimeOut() throws Exception {
        Account admin = account(Role.ADMIN);
        Subscription redirecting = subscribe(admin, "/hook/moved");
        server.reply(new HttpsTestServer.Reply(302, server.url("/hook/elsewhere"), 0));
        UUID redirected = sendTest(admin, redirecting);
        dispatchUntilAttempts(redirected, 1);

        assertThat(delivery(redirected).get("response_code")).isEqualTo(302);
        assertThat(delivery(redirected).get("last_error")).isEqualTo("redirect-not-followed");
        assertThat(server.received()).extracting(HttpsTestServer.Received::path).containsExactly("/hook/moved");

        Subscription slow = subscribe(admin, "/hook/slow");
        server.reply(new HttpsTestServer.Reply(200, null, 2500));
        UUID timedOut = sendTest(admin, slow);
        dispatchUntilAttempts(timedOut, 1);

        assertThat(delivery(timedOut).get("last_error")).isEqualTo("timeout");
        assertThat(delivery(timedOut).get("status")).isEqualTo("PENDING");
    }

    @Test
    void courseUpdatesAreDeliveredToSubscribers() throws Exception {
        Account admin = account(Role.ADMIN);
        Subscription subscription = subscribe(admin, "/hook/courses");
        var course = course();

        perform(admin, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/v1/admin/courses/" + course.getId()), map("name", course.getName(), "term", "2027/1",
                "instructor", "Instructor Hook"));
        UUID id = jdbc.queryForObject("SELECT id FROM webhook_delivery WHERE subscription_id = ? "
                + "AND event = 'course.updated'", UUID.class, subscription.id());
        dispatchUntilAttempts(id, 1);

        assertThat(delivery(id).get("status")).isEqualTo("DELIVERED");
        JsonNode envelope = readJson(server.received().get(0).body());
        assertThat(envelope.get("data").get("courseId").asLong()).isEqualTo(course.getId());
        assertThat(envelope.get("data").get("action").asText()).isEqualTo("UPDATED");
    }

    private JsonNode readJson(byte[] body) throws Exception {
        return json.readTree(new String(body, StandardCharsets.UTF_8));
    }
}
