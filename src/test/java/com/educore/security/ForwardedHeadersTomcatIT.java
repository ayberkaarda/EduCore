package com.educore.security;

import com.educore.ipaccess.IpDenyRuleCache;
import com.educore.ipaccess.Ipv4;
import com.educore.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X-Forwarded-For handling on a real Tomcat ({@code server.forward-headers-strategy=native}, RemoteIpValve
 * narrowed by {@code ForwardedHeadersConfig}) together with {@code ClientIpResolver}: repeated header lines,
 * IPv4-mapped IPv6, port suffixes, malformed hops and an empty trust list. The client address is observed
 * through a deny rule for {@value #DENIED}: 403 means the request was attributed to that address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedHeadersTomcatIT extends AbstractIntegrationTest {

    static final String DENIED = "198.18.20.1";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    static void deny(JdbcTemplate jdbc, IpDenyRuleCache cache) {
        long value = Ipv4.parse(DENIED).toLong();
        jdbc.update("INSERT INTO ip_deny_rule (kind, value, start_ip, end_ip, source, created_at) "
                + "VALUES ('STATIC', ?, ?, ?, 'MANUAL', now())", DENIED, value, value);
        cache.invalidateAfterCommit();
    }

    static void undeny(JdbcTemplate jdbc, IpDenyRuleCache cache) {
        jdbc.update("DELETE FROM ip_deny_rule WHERE value = ?", DENIED);
        cache.invalidateAfterCommit();
    }

    static HttpResponse<String> get(int port, String... forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/public/site-facts")).GET();
        for (String value : forwardedFor) {
            request.header("X-Forwarded-For", value);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String code(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).get("code").asText();
    }

    /** 127.0.0.1 (the test client) acts as the trusted reverse proxy. */
    @Nested
    @TestPropertySource(properties = "educore.ipaccess.trusted-proxies=127.0.0.1")
    class TrustedProxy {

        @LocalServerPort
        int port;

        @Autowired
        JdbcTemplate jdbc;

        @Autowired
        IpDenyRuleCache cache;

        @BeforeEach
        void denyTheAddress() {
            deny(jdbc, cache);
        }

        @AfterEach
        void removeRule() {
            undeny(jdbc, cache);
        }

        @Test
        void repeatedHeaderLinesAreCombinedRightMostWins() throws Exception {
            HttpResponse<String> denied = get(port, "203.0.113.9", DENIED);
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(code(denied)).isEqualTo("ipaccess/denied");
            assertThat(get(port, DENIED, "203.0.113.9").statusCode()).isEqualTo(200);
        }

        @Test
        void mappedIpv6AndPortSuffixesAreTheSameClient() throws Exception {
            assertThat(get(port, "::ffff:" + DENIED).statusCode()).isEqualTo(403);
            assertThat(get(port, "::ffff:c612:1401").statusCode()).isEqualTo(403);
            assertThat(get(port, DENIED + ":40123").statusCode()).isEqualTo(403);
            assertThat(get(port, "198.18.20.2").statusCode()).isEqualTo(200);
        }

        @Test
        void malformedRightMostHopIsRefusedNotGuessed() throws Exception {
            HttpResponse<String> malformed = get(port, "not-an-address");
            assertThat(malformed.statusCode()).isEqualTo(400);
            assertThat(code(malformed)).isEqualTo("ipaccess/invalid-client-address");
            // Junk the client wrote left of the proxy-appended address is ignored.
            assertThat(get(port, "not-an-address, " + DENIED).statusCode()).isEqualTo(403);
            assertThat(get(port, "not-an-address, 203.0.113.9").statusCode()).isEqualTo(200);
        }
    }

    /** No trusted proxy: the header is ignored entirely and the socket peer (127.0.0.1) is the client. */
    @Nested
    @TestPropertySource(properties = "educore.ipaccess.trusted-proxies=")
    class EmptyTrust {

        @LocalServerPort
        int port;

        @Autowired
        JdbcTemplate jdbc;

        @Autowired
        IpDenyRuleCache cache;

        @BeforeEach
        void denyTheAddress() {
            deny(jdbc, cache);
        }

        @AfterEach
        void removeRule() {
            undeny(jdbc, cache);
        }

        @Test
        void forwardedForIsIgnored() throws Exception {
            assertThat(get(port, DENIED).statusCode()).isEqualTo(200);
            assertThat(get(port, "::ffff:" + DENIED).statusCode()).isEqualTo(200);
            assertThat(get(port, "not-an-address").statusCode()).isEqualTo(200);
        }
    }
}
