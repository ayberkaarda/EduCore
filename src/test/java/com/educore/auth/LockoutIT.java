package com.educore.auth;

import com.educore.entity.Account;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login lockout (R-01, AC-01): 5 failures of one (username, client key) pair within 15 minutes lock that pair for
 * 15 minutes (423 + Retry-After); other networks are never locked by them. Failures of one username across all
 * networks are slowed by a progressive delay (429 + Retry-After, 1 s doubling up to 30 s), from which a network
 * the owner already signed in from is exempt.
 */
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
    void fiveFailuresLockThePairEvenForTheCorrectPassword() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();
        fail(account.getUsername(), ip, 5);

        MvcResult locked = login(account.getUsername(), PASSWORD, ip);

        assertThat(locked.getResponse().getStatus()).isEqualTo(423);
        assertThat(Long.parseLong(locked.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isBetween(840L, 900L);
        assertThat(body(locked).get("type").asText()).isEqualTo("/problems/auth/account-locked");
        assertThat(locked.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(countEvents("AUTH_LOCKED", account.getId())).isEqualTo(1);
        // Attempts rejected by the lock or the throttle are not recorded, so they cannot extend either.
        assertThat(login(account.getUsername(), WRONG, ip).getResponse().getStatus()).isEqualTo(423);
        assertThat(login(account.getUsername(), WRONG, newIp()).getResponse().getStatus()).isEqualTo(429);
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

    /**
     * The attacker's network is locked; the owner is not: from a network the owner already signed in from, at once;
     * from a new network, after the short progressive delay (never the 15-minute lock).
     */
    @Test
    void anAttackersLockedPairDoesNotLockTheOwnerOnAnotherNetwork() throws Exception {
        Account account = createAccount(false);
        String home = newIp();
        assertThat(login(account.getUsername(), PASSWORD, home).getResponse().getStatus()).isEqualTo(200);
        String attacker = newIp();
        fail(account.getUsername(), attacker, 5);
        assertThat(login(account.getUsername(), PASSWORD, attacker).getResponse().getStatus())
                .as("the attacker's pair is locked even with the right password").isEqualTo(423);

        String travelling = newIp();
        MvcResult slowed = login(account.getUsername(), PASSWORD, travelling);
        assertThat(slowed.getResponse().getStatus()).as("new network during an attack: slowed").isEqualTo(429);
        assertThat(body(slowed).get("code").asText()).isEqualTo("auth/too-many-attempts");
        assertThat(Long.parseLong(slowed.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 30L);

        assertThat(login(account.getUsername(), PASSWORD, home).getResponse().getStatus())
                .as("known network: neither locked nor delayed").isEqualTo(200);
        assertThat(login(account.getUsername(), PASSWORD, travelling).getResponse().getStatus())
                .as("the owner's success ended the streak").isEqualTo(200);
        assertThat(login(account.getUsername(), PASSWORD, attacker).getResponse().getStatus())
                .as("the attacker's pair stays locked").isEqualTo(423);
    }

    /**
     * Distributed guessing (every attempt from a fresh network, so no pair ever locks) is slowed progressively:
     * after 5 free failures each further verification must wait 1, 2, 4, 8, 16, then at most 30 seconds.
     */
    @Test
    void distributedFailuresGetProgressivelySlower() throws Exception {
        String username = "it-distributed-" + newIp();
        for (int i = 0; i < 5; i++) {
            fail(username, newIp(), 1);
        }
        long[] expectedDelays = {1, 2, 4, 8, 16, 30, 30};
        for (long expected : expectedDelays) {
            MvcResult throttled = login(username, WRONG, newIp());
            assertThat(throttled.getResponse().getStatus()).isEqualTo(429);
            assertThat(Long.parseLong(throttled.getResponse().getHeader(HttpHeaders.RETRY_AFTER)))
                    .as("delay after %d failures", attempts(username)).isEqualTo(expected);

            shiftAttempts(username, expected + " seconds");
            fail(username, newIp(), 1);
        }
        assertThat(attempts(username)).isEqualTo(5 + expectedDelays.length);
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
