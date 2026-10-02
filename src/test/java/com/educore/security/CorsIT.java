package com.educore.security;

import com.educore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * One CORS policy for the whole API ({@code SecurityConfig.corsConfigurationSource}): allowed origins from
 * {@code educore.cors.allowed-origins} ({@code http://localhost:3000} in the test profile), explicit methods and
 * headers, {@code Access-Control-Max-Age: 600}, credentials only under {@code /api/v1/auth/**}.
 */
@AutoConfigureMockMvc
class CorsIT extends AbstractIntegrationTest {

    private static final String ALLOWED = "http://localhost:3000";
    private static final String FOREIGN = "https://evil.example";

    @Autowired
    private MockMvc mockMvc;

    private MockHttpServletResponse preflight(String path, String origin, String method, String headers)
            throws Exception {
        return mockMvc.perform(options(path)
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers))
                .andReturn().getResponse();
    }

    @Test
    void allowedOriginPreflightIsAnsweredWithTheExplicitPolicy() throws Exception {
        MockHttpServletResponse response = preflight("/api/v1/admin/accounts", ALLOWED, "DELETE",
                "authorization, content-type");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).isEqualTo("GET,HEAD,POST,PUT,DELETE,OPTIONS");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                .containsIgnoringCase("authorization").containsIgnoringCase("content-type");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE)).isEqualTo("600");
        // Bearer-token routes never allow credentials (cookies) cross-origin.
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void onlyTheAuthCookiePathAllowsCredentials() throws Exception {
        MockHttpServletResponse refresh = preflight("/api/v1/auth/refresh", ALLOWED, "POST", "content-type");

        assertThat(refresh.getStatus()).isEqualTo(200);
        assertThat(refresh.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED);
        assertThat(refresh.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
        assertThat(refresh.getHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE)).isEqualTo("600");

        MockHttpServletResponse actual = mockMvc.perform(get("/api/v1/public/site-facts")
                .header(HttpHeaders.ORIGIN, ALLOWED)).andReturn().getResponse();
        assertThat(actual.getStatus()).isEqualTo(200);
        assertThat(actual.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED);
        assertThat(actual.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
        assertThat(actual.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)).contains("Retry-After", "X-Request-Id");
    }

    @Test
    void disallowedOriginReceivesNoAllowOriginHeader() throws Exception {
        MockHttpServletResponse preflight = preflight("/api/v1/auth/refresh", FOREIGN, "POST", "content-type");
        assertThat(preflight.getStatus()).isEqualTo(403);
        assertThat(preflight.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        assertThat(preflight.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
        assertThat(preflight.getContentType()).startsWith("application/problem+json");
        assertThat(preflight.getContentAsString()).contains("\"code\":\"request/cors-rejected\"");

        MockHttpServletResponse actual = mockMvc.perform(get("/api/v1/public/site-facts")
                .header(HttpHeaders.ORIGIN, FOREIGN)).andReturn().getResponse();
        assertThat(actual.getStatus()).isEqualTo(403);
        assertThat(actual.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void methodsAndHeadersOutsideTheListAreRejected() throws Exception {
        MockHttpServletResponse patch = preflight("/api/v1/me", ALLOWED, "PATCH", "content-type");
        assertThat(patch.getStatus()).isEqualTo(403);
        assertThat(patch.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();

        MockHttpServletResponse header = preflight("/api/v1/me", ALLOWED, "GET", "x-custom-header");
        assertThat(header.getStatus()).isEqualTo(403);
        assertThat(header.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }
}
