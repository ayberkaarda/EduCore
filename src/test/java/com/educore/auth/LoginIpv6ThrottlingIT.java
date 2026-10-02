package com.educore.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Through the real application filter chain (IP access filter, rate limit filter, controller, login limiter): the
 * login limit ({@code educore.security.login.ip-attempts-per-minute}, 10) is shared by every address of an IPv6
 * /64 and by the mapped spellings of an IPv4 address, so address rotation buys no extra attempts (R-04).
 */
class LoginIpv6ThrottlingIT extends AuthIntegrationSupport {

    private static final String WRONG = "wrong-value-for-test";

    /** A /64 prefix no other test uses, e.g. {@code 2001:db8:1a2b:3c4d}. */
    private static String freshSlash64() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "2001:db8:" + Integer.toHexString(random.nextInt(1, 0xffff)) + ":"
                + Integer.toHexString(random.nextInt(1, 0xffff));
    }

    /** A failed login for a fresh unknown username (never locked or throttled per account) from {@code peer}. */
    private MvcResult failFrom(String peer) throws Exception {
        return login("it-v6-" + UUID.randomUUID(), WRONG, peer);
    }

    @Test
    void rotatingAddressesInsideOneSlash64ShareOneLoginBucket() throws Exception {
        String prefix = freshSlash64();
        for (int i = 1; i <= 10; i++) {
            assertThat(failFrom(prefix + ":" + Integer.toHexString(i) + ":0:0:1").getResponse().getStatus())
                    .as("attempt %d", i).isEqualTo(401);
        }

        MvcResult throttled = failFrom(prefix + ":ffff:eeee:dddd:cccc");

        assertThat(throttled.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(throttled).get("code").asText()).isEqualTo("auth/too-many-attempts");
        assertThat(throttled.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(failFrom(freshSlash64() + "::1").getResponse().getStatus())
                .as("another /64 has its own bucket").isEqualTo(401);
    }

    @Test
    void ipv4MappedPeersShareTheBucketOfTheIpv4Address() throws Exception {
        String ip = newIp();
        for (int i = 1; i <= 10; i++) {
            String peer = i % 2 == 0 ? ip : "::ffff:" + ip;
            assertThat(failFrom(peer).getResponse().getStatus()).as("attempt %d from %s", i, peer).isEqualTo(401);
        }

        assertThat(failFrom("::ffff:" + ip).getResponse().getStatus()).isEqualTo(429);
        assertThat(failFrom(ip).getResponse().getStatus()).isEqualTo(429);
    }
}
