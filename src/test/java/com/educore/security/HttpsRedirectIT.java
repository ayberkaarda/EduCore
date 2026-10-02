package com.educore.security;

import com.educore.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production HTTPS requirement ({@code educore.security.https.required=true}, as in {@code application-prod.yml})
 * on a real Tomcat with {@code server.forward-headers-strategy=native}. The test client connects from 127.0.0.1
 * over plain HTTP, the way the TLS-terminating reverse proxy does. Design (docs/security/HEADERS.md): a request
 * that is not secure (no TLS and no {@code X-Forwarded-Proto: https} from a trusted proxy) is answered 403
 * {@code request/https-required}, never redirected; secure requests carry HSTS. The management port is excluded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"educore.security.https.required=true", "server.forward-headers-strategy=native"})
class HttpsRedirectIT extends AbstractIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    static HttpResponse<String> get(int port, String path, String forwardedProto) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (forwardedProto != null) {
            request.header("X-Forwarded-Proto", forwardedProto);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void assertHttpsRequired(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Strict-Transport-Security")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(JSON.readTree(response.body()).get("code").asText()).isEqualTo("request/https-required");
        // Written before Spring Security's header writer runs, the answer still carries the full policy.
        assertThat(response.headers().firstValue("Content-Security-Policy")).contains(SecurityHeaders.API_CSP);
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("strict-origin-when-cross-origin");
        assertThat(response.headers().firstValue("Permissions-Policy")).contains(SecurityHeaders.PERMISSIONS_POLICY);
        assertThat(response.headers().firstValue("Cross-Origin-Opener-Policy")).contains("same-origin");
    }

    /** The reverse proxy (127.0.0.1 here) is trusted: its X-Forwarded-Proto decides. */
    @Nested
    @TestPropertySource(properties = "educore.ipaccess.trusted-proxies=127.0.0.1")
    class BehindTrustedProxy {

        @LocalServerPort
        int port;

        @LocalManagementPort
        int managementPort;

        @Test
        void plainHttpIsRefusedWithoutRedirect() throws Exception {
            assertHttpsRequired(get(port, "/api/v1/public/site-facts", null));
            assertHttpsRequired(get(port, "/api/v1/public/site-facts", "http"));
            // Also before authentication: an unauthenticated call is refused for the transport, not the token.
            assertHttpsRequired(get(port, "/api/v1/me", null));
        }

        @Test
        void forwardedHttpsIsServedWithHsts() throws Exception {
            HttpResponse<String> response = get(port, "/api/v1/public/site-facts", "https");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Strict-Transport-Security"))
                    .contains("max-age=63072000 ; includeSubDomains ; preload")
                    .contains(SecurityHeaders.HSTS);
            assertThat(response.headers().firstValue("Content-Security-Policy")).contains(SecurityHeaders.API_CSP);
        }

        @Test
        void managementPortIsExcluded() throws Exception {
            HttpResponse<String> health = get(managementPort, "/actuator/health", null);

            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"status\":\"UP\"");
            // Same header policy, no HSTS and no HTTPS requirement on the internal management port.
            assertThat(health.headers().firstValue("Content-Security-Policy")).contains(SecurityHeaders.API_CSP);
            assertThat(health.headers().firstValue("X-Frame-Options")).contains("DENY");
            assertThat(health.headers().firstValue("Strict-Transport-Security")).isEmpty();
            // Neither IP deny rules nor rate limits apply there: a protected endpoint answers the plain 401.
            HttpResponse<String> metrics = get(managementPort, "/actuator/metrics", null);
            assertThat(metrics.statusCode()).isEqualTo(401);
            assertThat(JSON.readTree(metrics.body()).get("code").asText()).isEqualTo("auth/unauthenticated");
        }
    }

    /** 127.0.0.1 is not a trusted proxy: its X-Forwarded-Proto is ignored, so it cannot claim HTTPS. */
    @Nested
    @TestPropertySource(properties = "educore.ipaccess.trusted-proxies=10.9.9.9")
    class FromAnUntrustedPeer {

        @LocalServerPort
        int port;

        @Test
        void forwardedProtoIsIgnored() throws Exception {
            assertHttpsRequired(get(port, "/api/v1/public/site-facts", "https"));
        }
    }
}
