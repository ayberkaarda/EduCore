package com.educore.ipaccess;

import com.educore.authz.AuthzIntegrationSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 20 failed logins from one client IP within 10 minutes write a one-hour deny rule ({@code source=AUTO}) for
 * that IP. AUTO rules are login-scoped: the next login from the address is refused with 403
 * {@code ipaccess/denied}, every other request (other users behind the same NAT) is still served. The per-IP
 * login limit is raised here so that the 20 attempts are not cut short by the 10-per-minute login throttle.
 */
@TestPropertySource(properties = {
        "educore.ipaccess.auto-deny.failures=20",
        "educore.ipaccess.auto-deny.window=10m",
        "educore.ipaccess.auto-deny.duration=1h",
        "educore.security.login.ip-attempts-per-minute=1000"})
class AutoDenyIT extends AuthzIntegrationSupport {

    private static final String ATTACKER = "198.18.200.1";
    private static final String NEIGHBOUR = "198.18.200.2";
    private static final String RACED = "198.18.200.3";

    @Autowired
    private IpAutoDenyService autoDeny;

    @Autowired
    private IpDenyRuleCache cache;

    @AfterEach
    void removeRules() {
        jdbc.update("DELETE FROM ip_deny_rule WHERE value IN (?, ?, ?)", ATTACKER, NEIGHBOUR, RACED);
        cache.invalidateAfterCommit();
    }

    private int failedLogin(String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").with(from(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "auto-deny-" + UUID.randomUUID(),
                                "password", "wrong-value-for-test"))))
                .andReturn().getResponse().getStatus();
    }

    private int publicRequest(String ip) throws Exception {
        return mockMvc.perform(get("/api/v1/public/site-facts").with(from(ip))).andReturn().getResponse().getStatus();
    }

    @Test
    void twentyFailedLoginsDenyLoginsFromTheIpForOneHour() throws Exception {
        Instant before = Instant.now();
        for (int i = 1; i < 20; i++) {
            assertThat(failedLogin(ATTACKER)).as("failure %d", i).isEqualTo(401);
            assertThat(failedLogin(NEIGHBOUR)).isEqualTo(401);
        }
        assertThat(publicRequest(ATTACKER)).as("19 failures are below the threshold").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ip_deny_rule WHERE value = ?", Long.class, ATTACKER))
                .isZero();

        assertThat(failedLogin(ATTACKER)).as("the 20th failure is still answered").isEqualTo(401);

        MvcResult denied = mockMvc.perform(post("/api/v1/auth/login").with(from(ATTACKER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "x", "password", "y")))).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(denied).get("code").asText()).isEqualTo("ipaccess/denied");
        assertThat(publicRequest(ATTACKER)).as("AUTO rules only block the login").isEqualTo(200);
        assertThat(mockMvc.perform(get("/api/v1/me").with(from(ATTACKER))).andReturn().getResponse().getStatus())
                .as("an existing session would still work; without a token the API answers 401, not 403")
                .isEqualTo(401);
        assertThat(failedLogin(NEIGHBOUR)).as("19 failures from the neighbour").isEqualTo(401);

        Map<String, Object> rule = jdbc.queryForMap(
                "SELECT kind, source, start_ip, end_ip, expires_at, created_by, id FROM ip_deny_rule WHERE value = ?",
                ATTACKER);
        assertThat(rule.get("kind")).isEqualTo("STATIC");
        assertThat(rule.get("source")).isEqualTo("AUTO");
        assertThat(rule.get("start_ip")).isEqualTo(Ipv4.parse(ATTACKER).toLong());
        assertThat(rule.get("end_ip")).isEqualTo(Ipv4.parse(ATTACKER).toLong());
        assertThat(rule.get("created_by")).isNull();
        Instant expiresAt = ((java.sql.Timestamp) rule.get("expires_at")).toInstant();
        assertThat(expiresAt).isBetween(before.plus(Duration.ofMinutes(59)), Instant.now().plus(Duration.ofMinutes(61)));

        JsonNode details = json.readTree(jdbc.queryForObject("SELECT details::text FROM security_event "
                + "WHERE type = 'IP_RULE_CHANGED' AND ip = ? ORDER BY id DESC LIMIT 1", String.class, ATTACKER));
        assertThat(details.get("action").asText()).isEqualTo("AUTO_CREATED");
        assertThat(details.get("ipRuleId").asLong()).isEqualTo(((Number) rule.get("id")).longValue());
        assertThat(details.get("source").asText()).isEqualTo("AUTO");
    }

    /** Concurrent triggers for one address (threads or instances) leave exactly one AUTO row (V13 index). */
    @Test
    void concurrentTriggersWriteOneRuleAndExtendIt() throws Exception {
        Ipv4 address = Ipv4.parse(RACED);
        Instant now = Instant.now();
        int threads = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Long>> ids = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                ids.add(pool.submit(() -> {
                    start.await();
                    return autoDeny.deny(address, now);
                }));
            }
            start.countDown();
            java.util.Set<Long> distinct = new java.util.HashSet<>();
            for (java.util.concurrent.Future<Long> id : ids) {
                distinct.add(id.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(distinct).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ip_deny_rule WHERE source = 'AUTO' AND start_ip = ?",
                Long.class, address.toLong())).isOne();

        autoDeny.deny(address, now.plus(Duration.ofMinutes(30)));
        java.sql.Timestamp expires = jdbc.queryForObject(
                "SELECT expires_at FROM ip_deny_rule WHERE source = 'AUTO' AND start_ip = ?",
                java.sql.Timestamp.class, address.toLong());
        assertThat(expires.toInstant()).isAfter(now.plus(Duration.ofMinutes(89)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_event WHERE type = 'IP_RULE_CHANGED' "
                + "AND ip = ? AND details->>'action' = 'AUTO_CREATED'", Long.class, RACED)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_event WHERE type = 'IP_RULE_CHANGED' "
                + "AND ip = ? AND details->>'action' = 'AUTO_EXTENDED'", Long.class, RACED)).isEqualTo(threads);
    }

    /**
     * IPv6 (R-04): failures are counted per /64, so rotating the interface identifier does not reset the count, and
     * the /64 is then denied the login endpoint (in memory; deny rules are IPv4-only). Other networks and every
     * other route of the same /64 are unaffected.
     */
    @Test
    void twentyFailuresSpreadOverOneIpv6Slash64DenyLoginsFromTheWholeNetwork() throws Exception {
        String prefix = "2001:db8:ad:" + Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(1, 0xffff));
        for (int i = 1; i <= 20; i++) {
            assertThat(failedLogin(prefix + ":" + Integer.toHexString(i) + "::1")).as("failure %d", i).isEqualTo(401);
        }

        MvcResult denied = mockMvc.perform(post("/api/v1/auth/login").with(from(prefix + ":abcd:ef01:2345:6789"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "x", "password", "y")))).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(denied).get("code").asText()).isEqualTo("ipaccess/denied");
        assertThat(publicRequest(prefix + "::77")).as("AUTO denials only block the login").isEqualTo(200);
        assertThat(failedLogin("2001:db8:ae::1")).as("another /64").isEqualTo(401);

        String network = ClientAddress.clientKey(prefix + "::1");
        JsonNode details = json.readTree(jdbc.queryForObject("SELECT details::text FROM security_event "
                + "WHERE type = 'IP_RULE_CHANGED' AND ip = ? ORDER BY id DESC LIMIT 1", String.class, network));
        assertThat(details.get("action").asText()).isEqualTo("AUTO_CREATED");
        assertThat(details.get("kind").asText()).isEqualTo("IPV6_NETWORK");
        assertThat(details.get("source").asText()).isEqualTo("AUTO");
        assertThat(details.has("ipRuleId")).isFalse();
        assertThat(autoDeny.isLoginDenied(ClientAddress.parse(prefix + ":1:2:3:4"))).isTrue();
        assertThat(autoDeny.isLoginDenied(ClientAddress.parse(ATTACKER))).as("IPv4 uses persisted rules").isFalse();
    }
}
