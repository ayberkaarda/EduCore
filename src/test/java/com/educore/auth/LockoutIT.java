package com.educore.auth;

import com.educore.entity.Account;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Per-account lockout: 5 failures within 15 minutes lock the username for 15 minutes (423 + Retry-After). */
class LockoutIT extends AuthIntegrationSupport {

    private static final String WRONG = "wrong-value-for-test";

    private void fail(String username, String ip, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            mockMvc.perform(loginRequest(username, WRONG, ip)).andExpect(status().isUnauthorized());
        }
    }

    private long attempts(String username) {
        return jdbc.queryForObject("SELECT count(*) FROM login_attempt WHERE username_hash = ?", Long.class,
                usernameHasher.hash(username));
    }

    private void shiftAttempts(String username, String interval) {
        jdbc.update("UPDATE login_attempt SET at = at - CAST(? AS interval) WHERE username_hash = ?", interval,
                usernameHasher.hash(username));
    }

    @Test
    void fiveFailuresLockTheAccountEvenForTheCorrectPassword() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        fail(account.getUsername(), ip, 5);

        MvcResult locked = login(account.getUsername(), PASSWORD, ip);

        assertThat(locked.getResponse().getStatus()).isEqualTo(423);
        assertThat(Long.parseLong(locked.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isBetween(840L, 900L);
        assertThat(body(locked).get("type").asText()).isEqualTo("/problems/auth/account-locked");
        assertThat(locked.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(countEvents("AUTH_LOCKED", account.getId())).isEqualTo(1);
        // Attempts rejected by the lock are not recorded, so they cannot extend it.
        assertThat(login(account.getUsername(), WRONG, newIp()).getResponse().getStatus()).isEqualTo(423);
        assertThat(attempts(account.getUsername())).isEqualTo(5);
    }

    @Test
    void unknownUsernamesLockTheSameWay() throws Exception {
        String unknown = "it-unknown-" + newIp();
        String ip = newIp();
        fail(unknown, ip, 5);

        MvcResult locked = login(unknown, WRONG, ip);

        assertThat(locked.getResponse().getStatus()).isEqualTo(423);
        assertThat(locked.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
    }

    @Test
    void lockEndsAfterFifteenMinutes() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        fail(account.getUsername(), ip, 5);
        assertThat(login(account.getUsername(), PASSWORD, ip).getResponse().getStatus()).isEqualTo(423);

        shiftAttempts(account.getUsername(), "16 minutes");

        assertThat(login(account.getUsername(), PASSWORD, ip).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void failuresSpreadOverMoreThanTheWindowDoNotLock() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        fail(account.getUsername(), ip, 4);
        shiftAttempts(account.getUsername(), "20 minutes");
        fail(account.getUsername(), ip, 1);

        assertThat(login(account.getUsername(), PASSWORD, ip).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aSuccessfulLoginEndsTheFailureStreak() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        fail(account.getUsername(), ip, 4);
        assertThat(login(account.getUsername(), PASSWORD, ip).getResponse().getStatus()).isEqualTo(200);
        fail(account.getUsername(), ip, 4);

        assertThat(login(account.getUsername(), PASSWORD, newIp()).getResponse().getStatus()).isEqualTo(200);
        assertThat(countEvents("AUTH_LOCKED", account.getId())).isZero();
    }
}
