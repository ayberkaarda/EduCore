package com.educore.auth;

import com.educore.config.EduCoreProperties;
import com.educore.entity.Account;
import com.educore.security.AuthenticatedUser;
import com.educore.security.JwtService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the documented residual validity of access tokens (docs/security/KEY_ROTATION.md): access tokens are
 * stateless, so logout and password change revoke refresh tokens only. An access token issued before either
 * stays usable until it expires: at most 15 minutes after issue plus 30 seconds of clock skew. A
 * soft-deleted or removed account loses access immediately because the account is loaded per request.
 */
class AccessTokenResidualValidityIT extends AuthIntegrationSupport {

    /** TEST DATA ONLY: a policy-compliant new password. */
    private static final String NEW_PASSWORD = "residual-test-new-value";

    @Autowired
    private EduCoreProperties properties;

    @Autowired
    private JwtService jwtService;

    @Test
    void accessTokensLiveFifteenMinutes() throws Exception {
        assertThat(properties.security().jwt().accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        Account account = createAccount(false);

        assertThat(body(login(account.getUsername(), PASSWORD, newIp())).get("expiresIn").asLong()).isEqualTo(900);
    }

    @Test
    void anAccessTokenIsAcceptedUpToThirtySecondsAfterExpiry() {
        Account account = createAccount(false);
        Instant now = Instant.now();
        String withinSkew = issuedAt(now.minus(Duration.ofMinutes(15)).minusSeconds(25), account);
        String beyondSkew = issuedAt(now.minus(Duration.ofMinutes(15)).minusSeconds(35), account);

        assertThat(jwtService.parse(withinSkew).accountId()).isEqualTo(account.getId());
        assertThatThrownBy(() -> jwtService.parse(beyondSkew)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void logoutLeavesTheIssuedAccessTokenValidUntilItExpires() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        MvcResult login = login(account.getUsername(), PASSWORD, ip);
        String accessToken = accessToken(body(login));
        String refreshToken = refreshToken(login);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .cookie(new Cookie(RefreshCookies.NAME, refreshToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(refreshRequest(refreshToken, ip)).andExpect(status().isUnauthorized());
        mockMvc.perform(meRequest(accessToken)).andExpect(status().isOk());
    }

    @Test
    void passwordChangeLeavesOlderAccessTokensValidUntilTheyExpire() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        MvcResult first = login(account.getUsername(), PASSWORD, ip);
        String otherDeviceAccessToken = accessToken(body(login(account.getUsername(), PASSWORD, ip)));

        mockMvc.perform(post("/api/v1/auth/password")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(body(first)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD,
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isOk());

        mockMvc.perform(refreshRequest(refreshToken(first), ip)).andExpect(status().isUnauthorized());
        mockMvc.perform(meRequest(otherDeviceAccessToken)).andExpect(status().isOk());
    }

    private String issuedAt(Instant instant, Account account) {
        return new JwtService(properties.security().jwt(), Clock.fixed(instant, ZoneOffset.UTC))
                .issue(AuthenticatedUser.of(account)).token();
    }
}
