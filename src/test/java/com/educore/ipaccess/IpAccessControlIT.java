package com.educore.ipaccess;

import com.educore.authz.AuthzIntegrationSupport;
import com.educore.entity.Account;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Request-level IP deny rules: STATIC, RANGE and CIDR rules deny the resolved client IP with 403
 * {@code ipaccess/denied}; {@code X-Forwarded-For} counts only from a trusted proxy; expired rules are ignored
 * and purged; the admin API refuses rules that would deny the caller or a trusted proxy. Rule addresses lie in
 * 198.18.0.0/16, where no other test sends requests from.
 */
@TestPropertySource(properties = "educore.ipaccess.trusted-proxies=" + IpAccessControlIT.PROXY + ",10.252.0.0/24")
class IpAccessControlIT extends AuthzIntegrationSupport {

    static final String PROXY = "10.253.0.1";

    @Autowired
    private IpDenyRuleCache cache;

    @Autowired
    private IpDenyRuleCleanup cleanup;

    private final List<Long> insertedRules = new ArrayList<>();

    @AfterEach
    void removeRules() {
        insertedRules.forEach(id -> jdbc.update("DELETE FROM ip_deny_rule WHERE id = ?", id));
        insertedRules.clear();
        cache.invalidateAfterCommit();
    }

    private void rule(IpRangeKind kind, String value, Instant expiresAt) {
        Ipv4Range range = IpRanges.parse(kind, value);
        insertedRules.add(jdbc.queryForObject("INSERT INTO ip_deny_rule (kind, value, start_ip, end_ip, reason, source, "
                        + "expires_at, created_at) VALUES (?, ?, ?, ?, 'secret reason text', 'MANUAL', ?, now()) RETURNING id",
                Long.class, kind.name(), value, range.start().toLong(), range.end().toLong(),
                expiresAt == null ? null : Timestamp.from(expiresAt)));
        cache.invalidateAfterCommit();
    }

    private MvcResult request(String peer, String forwardedFor) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/public/site-facts").with(from(peer));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mockMvc.perform(request).andReturn();
    }

    private int status(String peer) throws Exception {
        return request(peer, null).getResponse().getStatus();
    }

    private void assertDenied(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        JsonNode problem = body(result);
        assertThat(problem.get("code").asText()).isEqualTo("ipaccess/denied");
        // No rule details: neither the matched value, the reason nor an expiry.
        String text = result.getResponse().getContentAsString();
        assertThat(text).doesNotContain("198.18").doesNotContain("secret reason").doesNotContain("expires");
    }

    @Test
    void staticRuleDeniesExactlyOneAddress() throws Exception {
        rule(IpRangeKind.STATIC, "198.18.1.10", null);

        assertDenied(request("198.18.1.10", null));
        assertThat(status("198.18.1.11")).isEqualTo(200);
        assertThat(status("198.18.1.9")).isEqualTo(200);
    }

    @Test
    void rangeRuleDeniesItsInclusiveBounds() throws Exception {
        rule(IpRangeKind.RANGE, "198.18.2.10-198.18.2.20", null);

        assertDenied(request("198.18.2.10", null));
        assertDenied(request("198.18.2.15", null));
        assertDenied(request("198.18.2.20", null));
        assertThat(status("198.18.2.9")).isEqualTo(200);
        assertThat(status("198.18.2.21")).isEqualTo(200);
    }

    @Test
    void cidrRuleDeniesTheWholeBlock() throws Exception {
        rule(IpRangeKind.CIDR, "198.18.3.0/24", null);

        assertDenied(request("198.18.3.0", null));
        assertDenied(request("198.18.3.255", null));
        assertThat(status("198.18.4.0")).isEqualTo(200);
        assertThat(status("198.18.2.255")).isEqualTo(200);
    }

    @Test
    void deniedRequestsAreRefusedBeforeAuthentication() throws Exception {
        rule(IpRangeKind.STATIC, "198.18.5.5", null);
        Account admin = account(Role.ADMIN);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/accounts").with(from("198.18.5.5"))
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andReturn();

        assertDenied(result);
    }

    @Test
    void trustedProxyForwardsTheClientAddress() throws Exception {
        rule(IpRangeKind.STATIC, "198.18.6.6", null);

        assertDenied(request(PROXY, "198.18.6.6"));
        // Several trusted proxies in a row: the right-most untrusted hop is the client.
        assertDenied(request(PROXY, "198.18.6.6, 10.252.0.9"));
        // A spoofed left-most value cannot hide the real client the proxy appended.
        assertDenied(request(PROXY, "203.0.113.200, 198.18.6.6"));
        // ... nor make the proxy deny an innocent client.
        assertThat(request(PROXY, "198.18.6.6, 203.0.113.201").getResponse().getStatus()).isEqualTo(200);
        assertThat(request(PROXY, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void untrustedPeerCannotSpoofItsAddress() throws Exception {
        rule(IpRangeKind.STATIC, "198.18.7.7", null);

        // The denied peer stays denied whatever header it sends ...
        assertDenied(request("198.18.7.7", "203.0.113.50"));
        // ... and an allowed peer is not denied by naming a denied address.
        assertThat(request("198.18.7.8", "198.18.7.7").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void preflightsFromDeniedAddressesAreRefusedToo() throws Exception {
        rule(IpRangeKind.STATIC, "198.18.13.13", null);

        MvcResult preflight = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .options("/api/v1/admin/accounts").with(from("198.18.13.13"))
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")).andReturn();

        assertDenied(preflight);
        assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void autoRulesBlockOnlyTheLogin() throws Exception {
        Ipv4Range range = IpRanges.parse(IpRangeKind.STATIC, "198.18.14.14");
        insertedRules.add(jdbc.queryForObject("INSERT INTO ip_deny_rule (kind, value, start_ip, end_ip, source, "
                + "expires_at, created_at) VALUES ('STATIC', '198.18.14.14', ?, ?, 'AUTO', now() + interval '1 hour', "
                + "now()) RETURNING id", Long.class, range.start().toLong(), range.end().toLong()));
        cache.invalidateAfterCommit();
        Account user = account(Role.USER);

        assertDenied(mockMvc.perform(post("/api/v1/auth/login").with(from("198.18.14.14"))
                .contentType("application/json").content("{\"username\":\"x\",\"password\":\"y\"}")).andReturn());
        assertThat(status("198.18.14.14")).isEqualTo(200);
        // A user already signed in behind the same (NAT) address keeps working.
        assertThat(mockMvc.perform(get("/api/v1/me").with(from("198.18.14.14"))
                .header(HttpHeaders.AUTHORIZATION, bearer(user))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void mappedIpv6AndPortFormsAreNormalisedBeforeTheDenyCheck() throws Exception {
        rule(IpRangeKind.STATIC, "198.18.15.15", null);

        assertDenied(request("::ffff:198.18.15.15", null));
        assertDenied(request(PROXY, "::ffff:198.18.15.15"));
        assertDenied(request(PROXY, "::ffff:c612:f0f"));
        assertDenied(request(PROXY, "198.18.15.15:51000"));
        // The proxy itself may appear mapped as well.
        assertDenied(request("::ffff:" + PROXY, "198.18.15.15"));
    }

    @Test
    void nativeIpv6ClientsPassByDefaultAndMalformedForwardedClientsAreRefused() throws Exception {
        assertThat(request(PROXY, "2001:db8::15").getResponse().getStatus()).isEqualTo(200);

        MvcResult malformed = request(PROXY, "not-an-address");
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(malformed).get("code").asText()).isEqualTo("ipaccess/invalid-client-address");
        // Client-supplied junk left of the proxy-appended address is ignored.
        assertThat(request(PROXY, "not-an-address, 203.0.113.77").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void expiredRulesAreIgnoredAndPurged() throws Exception {
        rule(IpRangeKind.STATIC, "198.18.8.8", Instant.now().minus(1, ChronoUnit.MINUTES));
        rule(IpRangeKind.STATIC, "198.18.8.9", Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(status("198.18.8.8")).isEqualTo(200);
        assertDenied(request("198.18.8.9", null));

        assertThat(cleanup.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ip_deny_rule WHERE value = '198.18.8.8'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ip_deny_rule WHERE value = '198.18.8.9'", Long.class))
                .isOne();
    }

    @Test
    void adminChangesTakeEffectImmediately() throws Exception {
        Account admin = account(Role.ADMIN);
        String target = "198.18.9.9";
        cleanUpDenyRuleValue(target);
        assertThat(status(target)).isEqualTo(200);

        MvcResult created = perform(admin, post("/api/v1/admin/ip-rules"),
                map("kind", "STATIC", "value", target, "reason", "abuse report"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode rule = body(created);
        assertThat(rule.get("kind").asText()).isEqualTo("STATIC");
        assertThat(rule.get("value").asText()).isEqualTo(target);
        assertThat(rule.get("startIp").asText()).isEqualTo(target);
        assertThat(rule.get("source").asText()).isEqualTo("MANUAL");
        assertThat(rule.get("createdBy").asLong()).isEqualTo(admin.getId());
        assertDenied(request(target, null));

        long id = rule.get("id").asLong();
        MvcResult moved = perform(admin, put("/api/v1/admin/ip-rules/" + id),
                map("kind", "CIDR", "value", "198.18.10.0/30"));
        cleanUpDenyRuleValue("198.18.10.0/30");
        assertThat(moved.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(moved).get("endIp").asText()).isEqualTo("198.18.10.3");
        assertThat(status(target)).isEqualTo(200);
        assertDenied(request("198.18.10.3", null));

        JsonNode page = body(perform(admin, get("/api/v1/admin/ip-rules").param("size", "5"), null));
        assertThat(page.get("size").asInt()).isEqualTo(5);
        assertThat(page.get("content").isArray()).isTrue();

        assertThat(perform(admin, delete("/api/v1/admin/ip-rules/" + id), null).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(status("198.18.10.3")).isEqualTo(200);
        assertThat(perform(admin, get("/api/v1/admin/ip-rules/" + id), null).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void adminCannotDenyTheirOwnAddressOrATrustedProxy() throws Exception {
        Account admin = account(Role.ADMIN);
        String own = "198.18.11.11";

        MvcResult self = mockMvc.perform(as(admin, post("/api/v1/admin/ip-rules"),
                map("kind", "STATIC", "value", own)).with(from(own))).andReturn();
        assertThat(self.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(self).get("code").asText()).isEqualTo("ip-rule/self-deny");

        MvcResult selfRange = mockMvc.perform(as(admin, post("/api/v1/admin/ip-rules"),
                map("kind", "CIDR", "value", "198.18.11.0/24")).with(from(own))).andReturn();
        assertThat(body(selfRange).get("code").asText()).isEqualTo("ip-rule/self-deny");

        // Behind a trusted proxy the caller's address is the forwarded one.
        MvcResult forwarded = mockMvc.perform(as(admin, post("/api/v1/admin/ip-rules"),
                map("kind", "STATIC", "value", own)).with(from(PROXY)).header("X-Forwarded-For", own)).andReturn();
        assertThat(body(forwarded).get("code").asText()).isEqualTo("ip-rule/self-deny");

        MvcResult proxy = perform(admin, post("/api/v1/admin/ip-rules"), map("kind", "CIDR", "value", "10.253.0.0/24"));
        assertThat(proxy.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(proxy).get("code").asText()).isEqualTo("ip-rule/trusted-proxy");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ip_deny_rule WHERE value IN ('198.18.11.11', "
                + "'198.18.11.0/24', '10.253.0.0/24')", Long.class)).isZero();
    }

    @Test
    void invalidValuesAreClearProblems() throws Exception {
        Account admin = account(Role.ADMIN);

        MvcResult ipv6 = perform(admin, post("/api/v1/admin/ip-rules"), map("kind", "STATIC", "value", "2001:db8::1"));
        assertThat(ipv6.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(ipv6).get("code").asText()).isEqualTo("ip-rule/ipv6-unsupported");

        for (String value : List.of("198.18.12.5/24", "198.18.12.9-198.18.12.1", "198.18.12.256", "01.2.3.4")) {
            MvcResult invalid = perform(admin, post("/api/v1/admin/ip-rules"),
                    map("kind", value.contains("/") ? "CIDR" : value.contains("-") ? "RANGE" : "STATIC", "value", value));
            assertThat(invalid.getResponse().getStatus()).as(value).isEqualTo(400);
            assertThat(body(invalid).get("code").asText()).as(value).isEqualTo("ip-rule/invalid");
        }

        MvcResult junk = perform(admin, post("/api/v1/admin/ip-rules"), map("kind", "STATIC", "value", "1.2.3.4 OR 1=1"));
        assertThat(body(junk).get("code").asText()).isEqualTo("request/invalid");

        MvcResult past = perform(admin, post("/api/v1/admin/ip-rules"),
                map("kind", "STATIC", "value", "198.18.12.7", "expiresAt", "2020-01-01T00:00:00Z"));
        assertThat(past.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(past).get("code").asText()).isEqualTo("request/invalid");
    }
}
