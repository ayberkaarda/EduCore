package com.educore.auth;

import com.educore.entity.Account;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/v1/auth/login}: response contract, refresh cookie, hash upgrade, audit and throttling. */
class LoginIT extends AuthIntegrationSupport {

    @Test
    void loginReturnsAccessTokenUserAndRefreshCookie() throws Exception {
        Account account = createAccount(false);

        MvcResult result = login(account.getUsername(), PASSWORD, newIp());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        JsonNode body = body(result);
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("accessToken", "expiresIn", "user");
        assertThat(accessToken(body)).isNotBlank();
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        JsonNode user = body.get("user");
        assertThat(user.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "firstName", "role", "mustChangePassword", "status");
        assertThat(user.get("id").asLong()).isEqualTo(account.getId());
        assertThat(user.get("firstName").asText()).isEqualTo("Test");
        assertThat(user.get("role").asText()).isEqualTo("USER");
        assertThat(user.get("mustChangePassword").asBoolean()).isFalse();
        assertThat(user.get("status").asText()).isEqualTo("ACTIVE");

        mockMvc.perform(meRequest(accessToken(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.getId()))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void refreshCookieHasTheRequiredAttributes() throws Exception {
        Account account = createAccount(false);

        String cookie = refreshSetCookie(login(account.getUsername(), PASSWORD, newIp()));

        assertThat(refreshTokenValue(cookie)).matches("[A-Za-z0-9_-]{43}");
        assertThat(cookie)
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=1209600")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict");
    }

    @Test
    void mustChangePasswordIsReturned() throws Exception {
        Account account = createAccount(true);

        mockMvc.perform(loginRequest(account.getUsername(), PASSWORD, newIp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true));
    }

    @Test
    void unknownUserAndWrongPasswordGetIdenticalResponses() throws Exception {
        Account account = createAccount(false);

        MvcResult wrongPassword = login(account.getUsername(), "wrong-value-for-test", newIp());
        MvcResult unknownUser = login("it-no-such-user-" + account.getId(), "wrong-value-for-test", newIp());

        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownUser.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownUser.getResponse().getContentType())
                .isEqualTo(wrongPassword.getResponse().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(unknownUser.getResponse().getContentAsString())
                .isEqualTo(wrongPassword.getResponse().getContentAsString());
        assertThat(unknownUser.getResponse().getHeaderNames())
                .containsExactlyInAnyOrderElementsOf(wrongPassword.getResponse().getHeaderNames());
        JsonNode problem = body(wrongPassword);
        assertThat(problem.get("type").asText()).isEqualTo("/problems/auth/invalid-credentials");
        assertThat(problem.get("status").asInt()).isEqualTo(401);
        assertThat(wrongPassword.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void legacyHashIsUpgradedToBcrypt12OnLogin() throws Exception {
        Account account = createAccountWithHash(new BCryptPasswordEncoder(10).encode(PASSWORD), false);
        assertThat(passwordEncoder.upgradeEncoding(account.getPassword())).isTrue();

        assertThat(login(account.getUsername(), PASSWORD, newIp()).getResponse().getStatus()).isEqualTo(200);

        String upgraded = accountRepository.findById(account.getId()).orElseThrow().getPassword();
        assertThat(upgraded).startsWith("{bcrypt}$2a$12$");
        assertThat(passwordEncoder.upgradeEncoding(upgraded)).isFalse();
        assertThat(login(account.getUsername(), PASSWORD, newIp()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void seededDemoAccountStillLogsInAndIsUpgraded() throws Exception {
        // Local demo password of the synthetic dev/test seed.
        assertThat(login("ali", "REMOVED-DB-PASSWORD", newIp()).getResponse().getStatus()).isEqualTo(200);

        assertThat(accountRepository.findByUsername("ali").orElseThrow().getPassword()).startsWith("{bcrypt}$2a$12$");
        assertThat(login("ali", "REMOVED-DB-PASSWORD", newIp()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void invalidBodiesAreRejectedWithProblemDetails() throws Exception {
        String ip = newIp();
        for (String body : List.of("{\"username\":\"\",\"password\":\"x\"}", "{\"username\":\"a\"}", "{not json",
                "{\"username\":\"a\",\"password\":\"" + "x".repeat(129) + "\"}")) {
            mockMvc.perform(post("/api/v1/auth/login").with(request -> {
                        request.setRemoteAddr(ip);
                        return request;
                    }).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                    .andExpect(jsonPath("$.type").value("/problems/auth/invalid-request"));
        }
    }

    @Test
    void loginEventsCarryIpAndRequestIdAndAttemptsStoreNoRawUsername() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();

        mockMvc.perform(loginRequest(account.getUsername(), PASSWORD, ip).header("X-Request-Id", "it-login-success-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "it-login-success-1"));
        MvcResult failure = mockMvc.perform(loginRequest(account.getUsername(), "wrong-value-for-test", ip)
                        .header("X-Request-Id", "bad id with spaces"))
                .andExpect(status().isUnauthorized()).andReturn();
        String generatedId = failure.getResponse().getHeader("X-Request-Id");
        assertThat(generatedId).matches("[0-9a-f-]{36}");

        Map<String, Object> success = jdbc.queryForMap("SELECT actor_account_id, ip, request_id FROM security_event "
                + "WHERE type = 'AUTH_LOGIN_SUCCESS' AND target_account_id = ?", account.getId());
        assertThat(success).containsEntry("actor_account_id", account.getId())
                .containsEntry("ip", ip).containsEntry("request_id", "it-login-success-1");
        Map<String, Object> failed = jdbc.queryForMap("SELECT ip, request_id, details::text AS details "
                + "FROM security_event WHERE type = 'AUTH_LOGIN_FAILURE' AND target_account_id = ?", account.getId());
        assertThat(failed).containsEntry("ip", ip).containsEntry("request_id", generatedId);
        assertThat((String) failed.get("details")).contains("bad_credentials").doesNotContain(account.getUsername());

        String hash = usernameHasher.hash(account.getUsername());
        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForList("SELECT success FROM login_attempt WHERE username_hash = ? ORDER BY id",
                Boolean.class, hash)).containsExactly(true, false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempt WHERE username_hash = ? OR ip = ?",
                Long.class, account.getUsername(), account.getUsername())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_event WHERE details::text LIKE ?",
                Long.class, "%" + account.getUsername() + "%")).isZero();
    }

    @Test
    void moreThanTenAttemptsPerMinuteFromOneIpAreThrottled() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(loginRequest("it-throttle-unknown-" + i, "wrong-value-for-test", ip))
                    .andExpect(status().isUnauthorized());
        }

        MvcResult throttled = login(account.getUsername(), PASSWORD, ip);

        assertThat(throttled.getResponse().getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(throttled.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
        assertThat(body(throttled).get("type").asText()).isEqualTo("/problems/auth/too-many-attempts");
        assertThat(login(account.getUsername(), PASSWORD, newIp()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void meRequiresABearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    private static String refreshTokenValue(String setCookie) {
        return setCookie.substring(RefreshCookies.NAME.length() + 1, setCookie.indexOf(';'));
    }
}
