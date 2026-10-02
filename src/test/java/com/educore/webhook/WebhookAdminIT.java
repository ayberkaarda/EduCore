package com.educore.webhook;

import com.educore.authz.AuthzIntegrationSupport;
import com.educore.entity.Account;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Webhook admin API with the production SSRF policy: secret shown once and stored encrypted, URL and event
 * validation, audit events, and the producers of {@code course.updated} and {@code account.deleted}.
 */
class WebhookAdminIT extends AuthzIntegrationSupport {

    private static final String URL = "https://admin-it.example.com/hook/";

    @Autowired
    private WebhookDispatcher dispatcher;

    @Autowired
    private WebhookPublisher publisher;

    @Autowired
    private WebhookRetention retention;

    @AfterEach
    void removeSubscriptions() {
        jdbc.update("DELETE FROM webhook_subscription WHERE url LIKE ?", URL + "%");
    }

    private JsonNode create(Account admin, List<String> events) throws Exception {
        MvcResult result = perform(admin, post("/api/v1/admin/webhooks"),
                map("url", URL + UUID.randomUUID(), "events", events));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result);
    }

    @Test
    void theSecretIsReturnedOnceAndStoredEncrypted() throws Exception {
        Account admin = account(Role.ADMIN);

        JsonNode created = create(admin, List.of("course.updated", "course.updated", "import.failed"));
        long id = created.get("webhook").get("id").asLong();
        String secret = created.get("secret").asText();

        assertThat(secret).matches("whsec_[0-9a-f]{64}");
        assertThat(created.get("webhook").get("events")).extracting(JsonNode::asText)
                .containsExactly("course.updated", "import.failed");
        assertThat(created.get("webhook").get("active").asBoolean()).isTrue();
        String stored = jdbc.queryForObject("SELECT secret_encrypted FROM webhook_subscription WHERE id = ?",
                String.class, id);
        assertThat(stored).startsWith("v1:").doesNotContain(secret).doesNotContain(secret.substring(6));
        String read = perform(admin, get("/api/v1/admin/webhooks/" + id), null).getResponse().getContentAsString();
        String listed = perform(admin, get("/api/v1/admin/webhooks"), null).getResponse().getContentAsString();
        assertThat(read).doesNotContain(secret).doesNotContain("secret");
        assertThat(listed).doesNotContain(secret).contains("\"id\":" + id);
        Integer audited = jdbc.queryForObject("SELECT count(*) FROM security_event WHERE type = 'WEBHOOK_CHANGED' "
                + "AND actor_account_id = ? AND details ->> 'action' = 'CREATED'", Integer.class, admin.getId());
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void urlsMustBePublicHttpsAndEventsKnown() throws Exception {
        Account admin = account(Role.ADMIN);

        for (String url : List.of("http://admin-it.example.com/x", "https://10.0.0.5/x", "https://169.254.169.254/x",
                "https://localhost/x", "https://[::1]/x")) {
            MvcResult result = perform(admin, post("/api/v1/admin/webhooks"), map("url", url,
                    "events", List.of("course.updated")));
            assertThat(result.getResponse().getStatus()).as(url).isEqualTo(400);
            assertThat(body(result).get("code").asText()).as(url).isEqualTo("webhook/invalid-url");
        }
        MvcResult unknownEvent = perform(admin, post("/api/v1/admin/webhooks"),
                map("url", URL + "x", "events", List.of("account.created")));
        assertThat(unknownEvent.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(unknownEvent).get("code").asText()).isEqualTo("request/invalid");
        MvcResult noEvents = perform(admin, post("/api/v1/admin/webhooks"), map("url", URL + "x", "events", List.of()));
        assertThat(noEvents.getResponse().getStatus()).isEqualTo(400);
        MvcResult testEventNotSubscribable = perform(admin, post("/api/v1/admin/webhooks"),
                map("url", URL + "x", "events", List.of("webhook.test")));
        assertThat(testEventNotSubscribable.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void updateDeleteAndUnknownIds() throws Exception {
        Account admin = account(Role.ADMIN);
        long id = create(admin, List.of("import.completed")).get("webhook").get("id").asLong();

        JsonNode updated = body(perform(admin, put("/api/v1/admin/webhooks/" + id),
                map("url", URL + "changed", "events", List.of("account.deleted"), "active", false)));
        assertThat(updated.get("url").asText()).isEqualTo(URL + "changed");
        assertThat(updated.get("active").asBoolean()).isFalse();
        assertThat(perform(admin, delete("/api/v1/admin/webhooks/" + id), null).getResponse().getStatus())
                .isEqualTo(204);
        MvcResult missing = perform(admin, get("/api/v1/admin/webhooks/" + id), null);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(missing).get("code").asText()).isEqualTo("webhook/not-found");
    }

    @Test
    void producersQueueEventsForSubscribersOnly() throws Exception {
        Account admin = account(Role.ADMIN);
        long courses = create(admin, List.of("course.updated")).get("webhook").get("id").asLong();
        long accounts = create(admin, List.of("account.deleted")).get("webhook").get("id").asLong();
        var course = course();
        Account student = account(Role.USER);

        perform(admin, put("/api/v1/admin/courses/" + course.getId()),
                map("name", course.getName(), "term", "2027/2", "instructor", "Instructor Admin IT"));
        perform(admin, delete("/api/v1/admin/accounts/" + student.getId()), null);

        List<Map<String, Object>> courseEvents = jdbc.queryForList(
                "SELECT event, payload::text AS payload FROM webhook_delivery WHERE subscription_id = ?", courses);
        assertThat(courseEvents).singleElement().satisfies(row -> {
            assertThat(row.get("event")).isEqualTo("course.updated");
            assertThat(row.get("payload").toString()).contains("\"courseId\": " + course.getId())
                    .contains("\"action\": \"UPDATED\"");
        });
        List<Map<String, Object>> accountEvents = jdbc.queryForList(
                "SELECT event, payload::text AS payload FROM webhook_delivery WHERE subscription_id = ?", accounts);
        assertThat(accountEvents).singleElement().satisfies(row -> {
            assertThat(row.get("event")).isEqualTo("account.deleted");
            assertThat(row.get("payload").toString()).contains("\"accountId\": " + student.getId())
                    .doesNotContain(student.getStudentNumber()).doesNotContain(student.getUsername());
        });

        MvcResult test = perform(admin, post("/api/v1/admin/webhooks/" + accounts + "/test"), null);
        assertThat(test.getResponse().getStatus()).isEqualTo(202);
        JsonNode deliveries = body(perform(admin, get("/api/v1/admin/webhooks/" + accounts + "/deliveries"), null));
        assertThat(deliveries.get("totalElements").asInt()).isEqualTo(2);
        assertThat(deliveries.get("content").get(0).get("event").asText()).isEqualTo("webhook.test");
        assertThat(deliveries.get("content").get(0).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void claimsAreFencedSoALateResultOfAnExpiredLeaseChangesNothing() throws Exception {
        Account admin = account(Role.ADMIN);
        long id = create(admin, List.of("course.updated")).get("webhook").get("id").asLong();
        jdbc.update("UPDATE webhook_delivery SET status = 'FAILED' WHERE status = 'PENDING'");
        UUID delivery = UUID.fromString(body(perform(admin, post("/api/v1/admin/webhooks/" + id + "/test"), null))
                .get("deliveryId").asText());

        WebhookDispatcher.Claim first = dispatcher.claimNext().orElseThrow();
        assertThat(first.id()).isEqualTo(delivery);
        assertThat(dispatcher.claimNext()).as("leased row is not claimed twice").isEmpty();

        jdbc.update("UPDATE webhook_delivery SET next_attempt_at = now() - interval '1 second' WHERE id = ?", delivery);
        WebhookDispatcher.Claim second = dispatcher.claimNext().orElseThrow();
        assertThat(second.token()).isNotEqualTo(first.token());

        assertThat(dispatcher.delivered(first, 200)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_delivery WHERE id = ?", String.class, delivery))
                .isEqualTo("PENDING");
        assertThat(dispatcher.failAttempt(second, 500, "http-500")).isTrue();
        assertThat(dispatcher.delivered(second, 200)).as("token is cleared after a result").isFalse();
        Map<String, Object> row = jdbc.queryForMap("SELECT status, attempt, claim_token FROM webhook_delivery "
                + "WHERE id = ?", delivery);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempt")).isEqualTo(1);
        assertThat(row.get("claim_token")).isNull();
    }

    @Test
    void subscriptionsTestEventsAndPendingDeliveriesAreCapped() throws Exception {
        Account admin = account(Role.ADMIN);
        long id = create(admin, List.of("course.updated")).get("webhook").get("id").asLong();
        for (int i = 0; i < 5; i++) {
            assertThat(perform(admin, post("/api/v1/admin/webhooks/" + id + "/test"), null).getResponse().getStatus())
                    .isEqualTo(202);
        }
        MvcResult throttled = perform(admin, post("/api/v1/admin/webhooks/" + id + "/test"), null);
        assertThat(throttled.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(throttled).get("code").asText()).isEqualTo("webhook/too-many-test-events");
        assertThat(Long.parseLong(throttled.getResponse().getHeader("Retry-After"))).isPositive();

        jdbc.update("INSERT INTO webhook_delivery (id, subscription_id, event, payload, attempt, status, "
                + "next_attempt_at, created_at) SELECT gen_random_uuid(), ?, 'course.updated', '{}'::jsonb, 0, "
                + "'PENDING', now() + interval '1 day', now() FROM generate_series(1, 995)", id);
        publisher.publish(WebhookEvent.COURSE_UPDATED, Map.of("courseId", 1));
        publisher.publish(WebhookEvent.COURSE_UPDATED, Map.of("courseId", 2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE subscription_id = ? "
                + "AND status = 'PENDING'", Integer.class, id)).isEqualTo(1000);
        JsonNode read = body(perform(admin, get("/api/v1/admin/webhooks/" + id), null));
        assertThat(read.get("droppedEvents").asLong()).isEqualTo(2);
        MvcResult full = perform(account(Role.ADMIN), post("/api/v1/admin/webhooks/" + id + "/test"), null);
        assertThat(full.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(full).get("code").asText()).isEqualTo("webhook/queue-full");

        Integer existing = jdbc.queryForObject("SELECT count(*) FROM webhook_subscription", Integer.class);
        for (int i = existing; i < 20; i++) {
            create(admin, List.of("import.failed"));
        }
        MvcResult tooMany = perform(admin, post("/api/v1/admin/webhooks"),
                map("url", URL + UUID.randomUUID(), "events", List.of("import.failed")));
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(tooMany).get("code").asText()).isEqualTo("webhook/limit-reached");
    }

    @Test
    void finishedDeliveriesExpireAfterTheRetentionPeriodPendingOnesStay() throws Exception {
        Account admin = account(Role.ADMIN);
        long id = create(admin, List.of("course.updated")).get("webhook").get("id").asLong();
        String insert = "INSERT INTO webhook_delivery (id, subscription_id, event, payload, attempt, status, "
                + "next_attempt_at, created_at) VALUES (?, ?, 'course.updated', '{}'::jsonb, 1, ?, now(), "
                + "now() - CAST(? AS interval))";
        UUID oldDelivered = UUID.randomUUID();
        UUID oldFailed = UUID.randomUUID();
        UUID oldPending = UUID.randomUUID();
        UUID recentDelivered = UUID.randomUUID();
        jdbc.update(insert, oldDelivered, id, "DELIVERED", "15 days");
        jdbc.update(insert, oldFailed, id, "FAILED", "15 days");
        jdbc.update(insert, oldPending, id, "PENDING", "15 days");
        jdbc.update(insert, recentDelivered, id, "DELIVERED", "13 days");

        assertThat(retention.purge()).isGreaterThanOrEqualTo(2);

        List<UUID> left = jdbc.queryForList("SELECT id FROM webhook_delivery WHERE subscription_id = ?", UUID.class, id);
        assertThat(left).contains(oldPending, recentDelivered).doesNotContain(oldDelivered, oldFailed);
    }
}
