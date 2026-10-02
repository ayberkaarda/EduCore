package com.educore.auth;

import com.educore.entity.Account;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Soft-deleted accounts ({@code status = DEACTIVATED}) cannot log in, refresh or use bearer tokens. */
class SoftDeletedAccountIT extends AuthIntegrationSupport {

    private void setDeleted(Account account, boolean deleted) {
        jdbc.update("UPDATE account SET status = ? WHERE id = ?", deleted ? "DEACTIVATED" : "ACTIVE",
                account.getId());
    }

    @Test
    void loginWithTheCorrectPasswordAnswersExactlyLikeInvalidCredentials() throws Exception {
        Account account = createAccount(false);
        setDeleted(account, true);

        MvcResult deleted = login(account.getUsername(), PASSWORD, newIp());
        MvcResult wrongPassword = login(account.getUsername(), "wrong-value-for-test", newIp());

        assertThat(deleted.getResponse().getStatus()).isEqualTo(401);
        assertThat(deleted.getResponse().getContentAsString())
                .isEqualTo(wrongPassword.getResponse().getContentAsString());
        assertThat(body(deleted).get("type").asText()).isEqualTo("/problems/auth/invalid-credentials");
        assertThat(deleted.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE account_id = ?", Long.class,
                account.getId())).isZero();
    }

    @Test
    void refreshIsRejectedWithoutConsumingTheToken() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        String token = refreshToken(login(account.getUsername(), PASSWORD, ip));
        setDeleted(account, true);

        mockMvc.perform(refreshRequest(token, ip))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/problems/auth/invalid-refresh-token"));

        // Nothing was issued and the token was not rotated: the transaction rolled back.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE account_id = ?", Long.class,
                account.getId())).isEqualTo(1);
        setDeleted(account, false);
        mockMvc.perform(refreshRequest(token, ip)).andExpect(status().isOk());
    }

    @Test
    void bearerTokenOfASoftDeletedAccountIsUnauthenticated() throws Exception {
        Account account = createAccount(false);
        String accessToken = accessToken(body(login(account.getUsername(), PASSWORD, newIp())));
        mockMvc.perform(meRequest(accessToken)).andExpect(status().isOk());

        setDeleted(account, true);

        mockMvc.perform(meRequest(accessToken)).andExpect(status().isUnauthorized());
    }
}
