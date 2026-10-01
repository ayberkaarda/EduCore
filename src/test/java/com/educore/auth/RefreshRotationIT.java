package com.educore.auth;

import com.educore.entity.Account;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/v1/auth/refresh} rotation and {@code POST /api/v1/auth/logout}. */
class RefreshRotationIT extends AuthIntegrationSupport {

    @Test
    void refreshRotatesTheTokenAndReturnsANewAccessToken() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        String first = refreshToken(login(account.getUsername(), PASSWORD, ip));

        MvcResult refreshed = mockMvc.perform(refreshRequest(first, ip)).andReturn();

        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(refreshed);
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(body.get("user").get("id").asLong()).isEqualTo(account.getId());
        mockMvc.perform(meRequest(accessToken(body))).andExpect(status().isOk());
        String setCookie = refreshSetCookie(refreshed);
        assertThat(setCookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth")
                .contains("Max-Age=" + java.time.Duration.ofDays(14).toSeconds());
        String second = refreshToken(refreshed);
        assertThat(second).isNotEqualTo(first);

        Map<String, Object> old = jdbc.queryForMap("SELECT family_id, revoked_at, replaced_by FROM refresh_token "
                + "WHERE token_hash = ?", RefreshTokenService.hash(first));
        Map<String, Object> current = jdbc.queryForMap("SELECT id, family_id, revoked_at, account_id, ip "
                + "FROM refresh_token WHERE token_hash = ?", RefreshTokenService.hash(second));
        assertThat(old.get("revoked_at")).isNotNull();
        assertThat(old.get("replaced_by")).isEqualTo(current.get("id"));
        assertThat(current.get("family_id")).isEqualTo(old.get("family_id"));
        assertThat(current.get("revoked_at")).isNull();
        assertThat(current).containsEntry("account_id", account.getId()).containsEntry("ip", ip);

        String third = refreshToken(mockMvc.perform(refreshRequest(second, ip))
                .andExpect(status().isOk()).andReturn());
        assertThat(third).isNotIn(first, second);
    }

    @Test
    void onlyTheHashOfTheRefreshTokenIsStored() throws Exception {
        Account account = createAccount(false);
        String value = refreshToken(login(account.getUsername(), PASSWORD, newIp()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE token_hash = ?", Long.class, value))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT token_hash FROM refresh_token WHERE account_id = ?", String.class,
                account.getId())).isEqualTo(RefreshTokenService.hash(value)).matches("[0-9a-f]{64}");
    }

    @Test
    void missingUnknownOrMalformedCookieIsRejectedAndCleared() throws Exception {
        String ip = newIp();
        for (String cookie : new String[]{null, "A".repeat(43), "not a token", "x".repeat(5000)}) {
            MvcResult result = mockMvc.perform(refreshRequest(cookie, ip)).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(body(result).get("type").asText()).isEqualTo("/problems/auth/invalid-refresh-token");
            assertThat(refreshSetCookie(result)).startsWith(RefreshCookies.NAME + "=;").contains("Max-Age=0");
        }
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        String value = refreshToken(login(account.getUsername(), PASSWORD, ip));
        jdbc.update("UPDATE refresh_token SET expires_at = now() - interval '1 second' WHERE token_hash = ?",
                RefreshTokenService.hash(value));

        mockMvc.perform(refreshRequest(value, ip)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheFamilyAndClearsTheCookie() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        String first = refreshToken(login(account.getUsername(), PASSWORD, ip));
        String second = refreshToken(mockMvc.perform(refreshRequest(first, ip)).andReturn());

        MvcResult logout = mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .cookie(new jakarta.servlet.http.Cookie(RefreshCookies.NAME, second)))
                .andExpect(status().isNoContent()).andReturn();

        assertThat(refreshSetCookie(logout)).startsWith(RefreshCookies.NAME + "=;")
                .contains("Max-Age=0", "Path=/api/v1/auth", "HttpOnly", "SameSite=Strict");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE account_id = ? AND revoked_at IS NULL",
                Long.class, account.getId())).isZero();
        mockMvc.perform(refreshRequest(second, ip)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutCookieStillSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isNoContent());
    }

    @Test
    void refreshReturnsTheCurrentUserState() throws Exception {
        Account account = createAccount(true);
        String ip = newIp();
        String value = refreshToken(login(account.getUsername(), PASSWORD, ip));

        mockMvc.perform(refreshRequest(value, ip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }
}
