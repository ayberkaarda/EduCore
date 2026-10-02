package com.educore.weather;

import com.educore.authz.AuthzIntegrationSupport;
import com.educore.entity.Account;
import com.educore.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** {@code GET /api/v1/weather}: 30 requests per account and minute, then 429 with Retry-After. */
class WeatherRateLimitIT extends AuthzIntegrationSupport {

    @Test
    void eachAccountGetsThirtyRequestsPerMinute() throws Exception {
        Account user = account(Role.USER);
        Account other = account(Role.USER);

        for (int i = 0; i < 30; i++) {
            MvcResult ok = perform(user, get("/api/v1/weather"), null);
            assertThat(ok.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(ok)).hasSize(3);
        }
        MvcResult limited = perform(user, get("/api/v1/weather"), null);

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(limited).get("code").asText()).isEqualTo("weather/too-many-requests");
        assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(perform(other, get("/api/v1/weather"), null).getResponse().getStatus()).isEqualTo(200);
    }
}
