package com.educore.auth;

import com.educore.entity.Account;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/v1/auth/password}. */
class PasswordChangeIT extends AuthIntegrationSupport {

    /** TEST DATA ONLY: a policy-compliant new password. */
    private static final String NEW_PASSWORD = "integration-test-new-value";

    private MockHttpServletRequestBuilder change(String accessToken, String current, String next, String ip)
            throws Exception {
        return post("/api/v1/auth/password")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", current, "newPassword", next)));
    }

    @Test
    void changeClearsMustChangeRevokesAllRefreshTokensAndStartsANewSession() throws Exception {
        Account account = createAccount(true);
        String ip = newIp();
        MvcResult firstLogin = login(account.getUsername(), PASSWORD, ip);
        String otherSession = refreshToken(login(account.getUsername(), PASSWORD, ip));

        MvcResult changed = mockMvc.perform(change(accessToken(body(firstLogin)), PASSWORD, NEW_PASSWORD, ip))
                .andReturn();

        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(changed);
        assertThat(body.get("user").get("mustChangePassword").asBoolean()).isFalse();
        String newSession = refreshToken(changed);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().isMustChangePassword()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE account_id = ? AND revoked_at IS NULL",
                Long.class, account.getId())).isEqualTo(1);
        mockMvc.perform(refreshRequest(refreshToken(firstLogin), ip)).andExpect(status().isUnauthorized());
        mockMvc.perform(refreshRequest(otherSession, ip)).andExpect(status().isUnauthorized());
        mockMvc.perform(refreshRequest(newSession, ip)).andExpect(status().isOk());

        assertThat(login(account.getUsername(), PASSWORD, newIp()).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(account.getUsername(), NEW_PASSWORD, newIp()).getResponse().getStatus()).isEqualTo(200);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getPassword()).startsWith("{bcrypt}$2a$12$");
        Map<String, Object> event = jdbc.queryForMap("SELECT actor_account_id, ip, request_id FROM security_event "
                + "WHERE type = 'PASSWORD_CHANGED' AND target_account_id = ?", account.getId());
        assertThat(event).containsEntry("actor_account_id", account.getId()).containsEntry("ip", ip);
        assertThat(event.get("request_id")).isNotNull();
    }

    @Test
    void wrongCurrentPasswordIsRejectedAndCountsAsAFailedAttempt() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        String token = accessToken(body(login(account.getUsername(), PASSWORD, ip)));

        mockMvc.perform(change(token, "wrong-value-for-test", NEW_PASSWORD, ip))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/auth/invalid-current-password"));

        assertThat(jdbc.queryForList("SELECT success FROM login_attempt WHERE username_hash = ? ORDER BY id",
                Boolean.class, usernameHasher.hash(account.getUsername()))).containsExactly(true, false);
        assertThat(passwordEncoder.matches(PASSWORD,
                accountRepository.findById(account.getId()).orElseThrow().getPassword())).isTrue();
    }

    @Test
    void policyViolationsAreListed() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        String token = accessToken(body(login(account.getUsername(), PASSWORD, ip)));

        assertViolation(token, ip, "short-value", "too_short");
        // On the SecLists 10k list and 12 characters long.
        assertViolation(token, ip, "unbelievable", "common_password");
        assertViolation(token, ip, PASSWORD, "same_as_current");
        assertViolation(token, ip, "x".repeat(129), "too_long");
        assertViolation(token, ip, "y".repeat(73), "too_many_bytes");

        assertThat(passwordEncoder.matches(PASSWORD,
                accountRepository.findById(account.getId()).orElseThrow().getPassword())).isTrue();
    }

    @Test
    void passwordChangeRequiresABearerToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"b\"}"))
                .andExpect(status().isUnauthorized());
    }

    private void assertViolation(String token, String ip, String candidate, String violation) throws Exception {
        mockMvc.perform(change(token, PASSWORD, candidate, ip))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/auth/password-policy"))
                .andExpect(jsonPath("$.violations").value(org.hamcrest.Matchers.hasItem(violation)));
    }
}
