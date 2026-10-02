package com.educore.ratelimit;

import com.educore.authz.AuthzIntegrationSupport;
import com.educore.entity.Account;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Request rate limits with the production values (60 anonymous per IP, 300 authenticated per account, 120 on
 * {@code /api/v1/public/**} per IP, per minute). Every test uses its own client IPs and accounts, so buckets never
 * leak between tests sharing this context.
 */
@TestPropertySource(properties = {
        "educore.ratelimit.anonymous-per-minute=60",
        "educore.ratelimit.authenticated-per-minute=300",
        "educore.ratelimit.public-per-minute=120",
        "educore.ipaccess.trusted-proxies=" + RateLimitIT.PROXY})
class RateLimitIT extends AuthzIntegrationSupport {

    static final String PROXY = "10.250.0.1";
    private static final AtomicInteger IPS = new AtomicInteger(1);

    /** A client IP used by no other test. */
    private static String freshIp() {
        int n = IPS.getAndIncrement();
        return "10.251." + ((n >> 8) & 0xff) + "." + (n & 0xff);
    }

    private int status(MockHttpServletRequestBuilder request, String peer) throws Exception {
        return mockMvc.perform(request.with(from(peer))).andReturn().getResponse().getStatus();
    }

    /** Sends {@code count} requests and asserts that none was rate limited. */
    private void exhaust(int count, java.util.function.Supplier<MockHttpServletRequestBuilder> request, String peer)
            throws Exception {
        for (int i = 0; i < count; i++) {
            assertThat(status(request.get(), peer)).as("request %d", i + 1).isNotEqualTo(429);
        }
    }

    private void assertTooManyRequests(MockHttpServletRequestBuilder request, String peer) throws Exception {
        MvcResult result = mockMvc.perform(request.with(from(peer))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        String retryAfter = result.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        JsonNode problem = body(result);
        assertThat(problem.get("code").asText()).isEqualTo("rate-limit/exceeded");
        assertThat(problem.get("status").asInt()).isEqualTo(429);
    }

    @Test
    void anonymousCallersGetSixtyRequestsPerMinutePerIp() throws Exception {
        String ip = freshIp();
        exhaust(60, () -> get("/api/v1/courses"), ip);

        assertTooManyRequests(get("/api/v1/courses"), ip);
        // Another IP has its own bucket.
        assertThat(status(get("/api/v1/courses"), freshIp())).isEqualTo(401);
        // A token that does not verify counts against the anonymous bucket of the IP.
        assertTooManyRequests(get("/api/v1/courses").header(HttpHeaders.AUTHORIZATION, "Bearer forged.token.value"), ip);
    }

    @Test
    void forwardedForFromAnUntrustedPeerDoesNotChangeTheKey() throws Exception {
        String peer = freshIp();
        for (int i = 0; i < 60; i++) {
            assertThat(status(get("/api/v1/courses").header("X-Forwarded-For", freshIp()), peer)).isEqualTo(401);
        }

        assertTooManyRequests(get("/api/v1/courses").header("X-Forwarded-For", freshIp()), peer);
    }

    @Test
    void forwardedForFromATrustedProxyKeysByTheForwardedClient() throws Exception {
        String client = freshIp();
        for (int i = 0; i < 60; i++) {
            // A client-supplied left-most value is ignored; the proxy-appended right-most hop is the key.
            assertThat(status(get("/api/v1/courses").header("X-Forwarded-For", freshIp() + ", " + client), PROXY))
                    .isEqualTo(401);
        }
        assertTooManyRequests(get("/api/v1/courses").header("X-Forwarded-For", client), PROXY);

        // Other clients behind the same proxy are unaffected: the proxy itself is never the key.
        assertThat(status(get("/api/v1/courses").header("X-Forwarded-For", freshIp()), PROXY)).isEqualTo(401);
    }

    @Test
    void publicApiAllowsOneHundredTwentyRequestsPerMinutePerIp() throws Exception {
        String ip = freshIp();
        exhaust(120, () -> get("/api/v1/public/site-facts"), ip);

        assertTooManyRequests(get("/api/v1/public/site-facts"), ip);
        // The public bucket is separate from the anonymous one of the same IP.
        assertThat(status(get("/api/v1/courses"), ip)).isEqualTo(401);
    }

    @Test
    void authenticatedCallersGetThreeHundredRequestsPerMinutePerAccount() throws Exception {
        Account user = account(Role.USER);
        Account other = account(Role.USER);
        String token = bearer(user);

        // Spread over many IPs: the bucket belongs to the account, not to an address.
        for (int i = 0; i < 300; i++) {
            assertThat(status(get("/api/v1/courses").header(HttpHeaders.AUTHORIZATION, token), freshIp()))
                    .as("request %d", i + 1).isEqualTo(200);
        }
        assertTooManyRequests(get("/api/v1/courses").header(HttpHeaders.AUTHORIZATION, token), freshIp());

        // Another account, and anonymous callers from any IP, still pass.
        assertThat(status(get("/api/v1/courses").header(HttpHeaders.AUTHORIZATION, bearer(other)), freshIp()))
                .isEqualTo(200);
        assertThat(status(get("/api/v1/courses"), freshIp())).isEqualTo(401);
    }

    /**
     * Structurally valid tokens naming a victim account (unsigned {@code alg=none}, or signed with another key)
     * never reach the victim's bucket: they count against the sender's anonymous IP bucket only.
     */
    @Test
    void forgedTokensNamingAVictimDoNotTouchTheVictimsBucket() throws Exception {
        Account victim = account(Role.USER);
        long exp = java.time.Instant.now().plusSeconds(600).getEpochSecond();
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String unsigned = b64.encodeToString("{\"alg\":\"none\"}".getBytes()) + "."
                + b64.encodeToString(("{\"sub\":\"" + victim.getId() + "\",\"iss\":\"educore\",\"aud\":\"educore-api\","
                + "\"exp\":" + exp + "}").getBytes()) + ".";
        byte[] foreignKey = new byte[32];
        new java.security.SecureRandom().nextBytes(foreignKey);
        String foreign = io.jsonwebtoken.Jwts.builder().subject(String.valueOf(victim.getId())).issuer("educore")
                .audience().add("educore-api").and()
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(foreignKey)).compact();

        for (int i = 0; i < 150; i++) {
            for (String forged : java.util.List.of(unsigned, foreign)) {
                assertThat(status(get("/api/v1/courses").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged),
                        freshIp())).isEqualTo(401);
            }
        }

        String token = bearer(victim);
        exhaust(300, () -> get("/api/v1/courses").header(HttpHeaders.AUTHORIZATION, token), freshIp());
        assertTooManyRequests(get("/api/v1/courses").header(HttpHeaders.AUTHORIZATION, token), freshIp());
    }

    /** IPv6 clients (here forwarded by the trusted proxy) share one bucket per /64 network. */
    @Test
    void ipv6ClientsAreKeyedByTheirSlash64() throws Exception {
        for (int i = 0; i < 60; i++) {
            String address = "2001:db8:5:" + Integer.toHexString(7) + "::" + Integer.toHexString(i + 1);
            assertThat(status(get("/api/v1/courses").header("X-Forwarded-For", address), PROXY)).isEqualTo(401);
        }
        assertTooManyRequests(get("/api/v1/courses").header("X-Forwarded-For", "2001:db8:5:7:ffff::1"), PROXY);
        assertThat(status(get("/api/v1/courses").header("X-Forwarded-For", "2001:db8:5:8::1"), PROXY)).isEqualTo(401);
    }

    @Test
    void retryAfterIsWholeSecondsAndAtLeastOne() {
        assertThat(RateLimitFilter.retryAfterSeconds(java.time.Duration.ZERO)).isEqualTo(1);
        assertThat(RateLimitFilter.retryAfterSeconds(java.time.Duration.ofMillis(1))).isEqualTo(1);
        assertThat(RateLimitFilter.retryAfterSeconds(java.time.Duration.ofMillis(1001))).isEqualTo(2);
        assertThat(RateLimitFilter.retryAfterSeconds(java.time.Duration.ofSeconds(60))).isEqualTo(60);
    }
}
