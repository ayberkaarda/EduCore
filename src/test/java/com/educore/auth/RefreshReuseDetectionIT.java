package com.educore.auth;

import com.educore.entity.Account;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Presenting an already rotated refresh token revokes its whole family and is audited. */
class RefreshReuseDetectionIT extends AuthIntegrationSupport {

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        String first = refreshToken(login(account.getUsername(), PASSWORD, ip));
        String second = refreshToken(mockMvc.perform(refreshRequest(first, ip)).andExpect(status().isOk()).andReturn());

        mockMvc.perform(refreshRequest(first, ip))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/problems/auth/invalid-refresh-token"));

        // The legitimate successor is revoked too: whoever holds the family must sign in again.
        mockMvc.perform(refreshRequest(second, ip)).andExpect(status().isUnauthorized());
        Object family = jdbc.queryForObject("SELECT family_id FROM refresh_token WHERE token_hash = ?", Object.class,
                RefreshTokenService.hash(first));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE family_id = ? AND revoked_at IS NULL",
                Long.class, family)).isZero();

        Map<String, Object> event = jdbc.queryForList("SELECT ip, request_id, details::text AS details "
                + "FROM security_event WHERE type = 'AUTH_REFRESH_REUSE' AND target_account_id = ? ORDER BY id",
                account.getId()).get(0);
        assertThat(event).containsEntry("ip", ip);
        assertThat(event.get("request_id")).isNotNull();
        assertThat((String) event.get("details")).contains(family.toString()).doesNotContain(first);
    }

    @Test
    void reuseDoesNotAffectOtherSessionsOfTheSameAccount() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        String sessionA = refreshToken(login(account.getUsername(), PASSWORD, ip));
        String sessionB = refreshToken(login(account.getUsername(), PASSWORD, ip));
        mockMvc.perform(refreshRequest(sessionA, ip)).andExpect(status().isOk());

        mockMvc.perform(refreshRequest(sessionA, ip)).andExpect(status().isUnauthorized());

        assertThat(countEvents("AUTH_REFRESH_REUSE", account.getId())).isEqualTo(1);
        mockMvc.perform(refreshRequest(sessionB, ip)).andExpect(status().isOk());
    }
}
