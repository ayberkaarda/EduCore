package com.educore.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-IP login throttling (10 attempts per minute) keys on the client IP from {@code X-Forwarded-For} only
 * when the direct peer is a trusted proxy; anyone else cannot escape the limit by varying the header.
 */
@TestPropertySource(properties = "educore.ipaccess.trusted-proxies=" + ForwardedForThrottlingIT.PROXY)
class ForwardedForThrottlingIT extends AuthIntegrationSupport {

    static final String PROXY = "10.255.255.254";
    private static final int LIMIT = 10;

    /** A failed login for a fresh unknown username (never locked), sent from {@code peer}. */
    private int attempt(String peer, String forwardedFor) throws Exception {
        MvcResult result = mockMvc.perform(loginRequest("it-xff-" + UUID.randomUUID(), "wrong-value-for-test", peer)
                        .header("X-Forwarded-For", forwardedFor))
                .andReturn();
        if (result.getResponse().getStatus() == 429) {
            assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
        }
        return result.getResponse().getStatus();
    }

    @Test
    void untrustedPeerCannotSpreadAttemptsOverForgedForwardedForValues() throws Exception {
        String peer = newIp();
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i <= LIMIT; i++) {
            statuses.add(attempt(peer, newIp()));
        }

        assertThat(statuses.subList(0, LIMIT)).containsOnly(401);
        assertThat(statuses.get(LIMIT)).isEqualTo(429);
        // Every attempt was attributed to the socket peer, none to a forged header value.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempt WHERE ip = ?", Long.class, peer))
                .isEqualTo(LIMIT);
    }

    @Test
    void trustedProxyIsThrottledPerForwardedClient() throws Exception {
        List<Integer> distinctClients = new ArrayList<>();
        for (int i = 0; i <= LIMIT; i++) {
            distinctClients.add(attempt(PROXY, newIp()));
        }
        String client = newIp();
        List<Integer> sameClient = new ArrayList<>();
        for (int i = 0; i <= LIMIT; i++) {
            // A client-supplied left-most value is ignored: the right-most untrusted hop is the client.
            sameClient.add(attempt(PROXY, newIp() + ", " + client));
        }

        assertThat(distinctClients).containsOnly(401);
        assertThat(sameClient.subList(0, LIMIT)).containsOnly(401);
        assertThat(sameClient.get(LIMIT)).isEqualTo(429);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempt WHERE ip = ?", Long.class, client))
                .isEqualTo(LIMIT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempt WHERE ip = ?", Long.class, PROXY))
                .isZero();
    }
}
