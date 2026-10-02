package com.educore.lifecycle;

import com.educore.authz.AuthzIntegrationSupport;
import com.educore.entity.Account;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared context of the lifecycle tests: the authorization fixtures plus a {@link MutableClock} that replaces the
 * application clock (token expiry, grace period, purge due date and retention all read it). The clock is reset
 * after every test.
 */
@Import(LifecycleIntegrationSupport.TestClockConfig.class)
abstract class LifecycleIntegrationSupport extends AuthzIntegrationSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    protected MutableClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    protected Clock clock() {
        return clock;
    }

    /** {@code DELETE /api/v1/me} with {@code currentPassword} as {@code account}. */
    protected MvcResult requestDeletion(Account account, String currentPassword) throws Exception {
        return perform(account, delete("/api/v1/me"), Map.of("currentPassword", currentPassword));
    }

    protected MvcResult login(Account account, String password) throws Exception {
        return perform(null, post("/api/v1/auth/login"),
                Map.of("username", account.getUsername(), "password", password));
    }

    /** The value of the {@code educore_rt} cookie set by {@code result}. */
    protected static String refreshCookie(MvcResult result) {
        List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("educore_rt=")).toList();
        assertThat(cookies).hasSize(1);
        String header = cookies.get(0);
        return header.substring("educore_rt=".length(), header.indexOf(';'));
    }

    protected MvcResult refresh(String cookie) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").with(from(newIp()))
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(new Cookie("educore_rt", cookie))).andReturn();
    }

    protected String accessToken(MvcResult login) throws Exception {
        JsonNode body = body(login);
        return body.get("accessToken").asText();
    }

    protected String status(Account account) {
        List<String> statuses = jdbc.queryForList("SELECT status FROM account WHERE id = ?", String.class,
                account.getId());
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    protected long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }
}
