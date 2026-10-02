package com.educore.security;

import com.educore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Response security headers (docs/security/HEADERS.md) on successful responses, problem responses and the
 * sitemap. HSTS is absent here (HTTPS is not required in the test profile); {@code HttpsRedirectIT} covers it.
 */
@AutoConfigureMockMvc
class SecurityHeadersIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static void assertApiHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Content-Security-Policy"))
                .isEqualTo("default-src 'none'; frame-ancestors 'none'")
                .isEqualTo(SecurityHeaders.API_CSP);
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(response.getHeader("Permissions-Policy"))
                .isEqualTo(SecurityHeaders.PERMISSIONS_POLICY)
                .contains("camera=()", "microphone=()", "geolocation=()");
        assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
        assertThat(response.getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    void successfulApiResponseCarriesTheFullHeaderSet() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/public/site-facts")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertApiHeaders(response);
        assertThat(response.getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow");
    }

    @Test
    void problemResponsesCarryTheHeadersToo() throws Exception {
        MockHttpServletResponse unauthenticated = mockMvc.perform(get("/api/v1/me")).andReturn().getResponse();
        assertThat(unauthenticated.getStatus()).isEqualTo(401);
        assertApiHeaders(unauthenticated);
        assertThat(unauthenticated.getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow");

        MockHttpServletResponse invalid = mockMvc.perform(post("/api/v1/auth/login")
                .contentType("application/json").content("{}")).andReturn().getResponse();
        assertThat(invalid.getStatus()).isEqualTo(400);
        assertApiHeaders(invalid);
    }

    @Test
    void sitemapHasTheSecurityHeadersButNoRobotsTag() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/sitemap.xml")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertApiHeaders(response);
        assertThat(response.getHeader("X-Robots-Tag")).isNull();
    }
}
