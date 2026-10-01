package com.educore.auth;

import com.educore.entity.Account;
import com.educore.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Races between refresh, logout, login and password change, driven through {@link AuthService} on
 * threads released together by a barrier. Each scenario is repeated so that both interleavings occur;
 * the asserted invariants must hold for every interleaving.
 */
class AuthConcurrencyIT extends AuthIntegrationSupport {

    /** TEST DATA ONLY: a policy-compliant new password. */
    private static final String NEW_PASSWORD = "concurrency-test-new-value";
    private static final String WRONG = "wrong-value-for-test";
    private static final String INVALID_REFRESH = "auth/invalid-refresh-token";

    @Autowired
    private AuthService authService;

    @Autowired
    private AccountLocks accountLocks;

    @Autowired
    private TransactionTemplate transactions;

    private static ClientInfo client() {
        return new ClientInfo(newIp(), "concurrency-test");
    }

    private AuthService.Session login(Account account, String password) {
        return authService.login(new LoginRequest(account.getUsername(), password), client());
    }

    private long activeTokens(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE account_id = ? AND revoked_at IS NULL",
                Long.class, accountId);
    }

    private void assertRefreshFails(String refreshToken) {
        assertThatThrownBy(() -> authService.refresh(refreshToken, client()))
                .isInstanceOfSatisfying(AuthProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo(INVALID_REFRESH));
    }

    @Test
    void concurrentRefreshesWithTheSameTokenSucceedExactlyOnce() throws Exception {
        Account account = createAccount(false);
        for (int round = 0; round < 5; round++) {
            String token = login(account, PASSWORD).refreshToken();
            List<Callable<AuthService.Session>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> authService.refresh(token, client()));
            }

            List<Concurrently.Result<AuthService.Session>> results = Concurrently.run(tasks);

            assertThat(results).filteredOn(Concurrently.Result::succeeded).hasSize(1);
            assertThat(results).filteredOn(result -> !result.succeeded())
                    .allSatisfy(result -> assertThat(result.problemCode()).isEqualTo(INVALID_REFRESH));
            // The losers presented a rotated token: reuse, so the winner's successor is revoked as well.
            String successor = results.stream().filter(Concurrently.Result::succeeded).findFirst().orElseThrow()
                    .value().refreshToken();
            assertRefreshFails(successor);
        }
        assertThat(activeTokens(account.getId())).isZero();
    }

    @Test
    void logoutRacingARotationLeavesTheFamilyUnusable() throws Exception {
        Account account = createAccount(false);
        for (int round = 0; round < 15; round++) {
            String token = login(account, PASSWORD).refreshToken();
            Object family = jdbc.queryForObject("SELECT family_id FROM refresh_token WHERE token_hash = ?",
                    Object.class, RefreshTokenService.hash(token));

            List<Concurrently.Result<Object>> results = Concurrently.run(
                    () -> authService.refresh(token, client()),
                    () -> {
                        authService.logout(token);
                        return null;
                    });

            assertThat(results.get(1).succeeded()).isTrue();
            if (results.get(0).succeeded()) {
                assertRefreshFails(((AuthService.Session) results.get(0).value()).refreshToken());
            } else {
                assertThat(results.get(0).problemCode()).isEqualTo(INVALID_REFRESH);
            }
            assertRefreshFails(token);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE family_id = ? "
                    + "AND revoked_at IS NULL", Long.class, family)).isZero();
            assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM refresh_token_family WHERE id = ?",
                    Boolean.class, family)).isTrue();
        }
        assertThat(activeTokens(account.getId())).isZero();
    }

    @Test
    void passwordChangeRacingALoginWithTheOldPasswordLeavesNoOldSessionUsable() throws Exception {
        String sharedHash = passwordEncoder.encode(PASSWORD);
        for (int round = 0; round < 6; round++) {
            Account account = createAccountWithHash(sharedHash, false);
            AuthenticatedUser user = AuthenticatedUser.of(account);

            List<Concurrently.Result<AuthService.Session>> results = Concurrently.run(
                    () -> authService.changePassword(user, new PasswordChangeRequest(PASSWORD, NEW_PASSWORD),
                            client()),
                    () -> login(account, PASSWORD));

            Concurrently.Result<AuthService.Session> change = results.get(0);
            Concurrently.Result<AuthService.Session> oldLogin = results.get(1);
            assertThat(change.succeeded()).as("password change, round %d", round).isTrue();
            if (oldLogin.succeeded()) {
                // The login finished first; the password change must have revoked its family.
                assertRefreshFails(oldLogin.value().refreshToken());
            } else {
                assertThat(oldLogin.problemCode()).isEqualTo("auth/invalid-credentials");
            }
            assertThat(activeTokens(account.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token_family WHERE account_id = ? "
                    + "AND revoked_at IS NULL", Long.class, account.getId())).isEqualTo(1);
            assertThat(authService.refresh(change.value().refreshToken(), client()).refreshToken()).isNotBlank();
        }
    }

    @Test
    void parallelFailuresFromManyIpsNeverPassTheLockThreshold() throws Exception {
        Account account = createAccount(false);
        List<Callable<AuthService.Session>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tasks.add(() -> login(account, WRONG));
        }

        List<Concurrently.Result<AuthService.Session>> results = Concurrently.run(tasks);

        assertThat(results).noneMatch(Concurrently.Result::succeeded);
        assertThat(results.stream().map(Concurrently.Result::problemCode).toList())
                .containsOnly("auth/invalid-credentials", "auth/account-locked")
                .filteredOn("auth/invalid-credentials"::equals).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempt WHERE username_hash = ?", Long.class,
                usernameHasher.hash(account.getUsername()))).isEqualTo(5);
        assertThatThrownBy(() -> login(account, PASSWORD))
                .isInstanceOfSatisfying(AuthProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("auth/account-locked"));
    }

    @Test
    void parallelWrongCurrentPasswordsShareTheLoginLockThreshold() throws Exception {
        Account account = createAccount(false);
        AuthenticatedUser user = AuthenticatedUser.of(account);
        List<Callable<AuthService.Session>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> authService.changePassword(user, new PasswordChangeRequest(WRONG, NEW_PASSWORD),
                    client()));
        }

        List<Concurrently.Result<AuthService.Session>> results = Concurrently.run(tasks);

        assertThat(results.stream().map(Concurrently.Result::problemCode).toList())
                .containsOnly("auth/invalid-current-password", "auth/account-locked")
                .filteredOn("auth/invalid-current-password"::equals).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempt WHERE username_hash = ?", Long.class,
                usernameHasher.hash(account.getUsername()))).isEqualTo(5);
        assertThatThrownBy(() -> login(account, PASSWORD))
                .isInstanceOfSatisfying(AuthProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("auth/account-locked"));
    }

    @Test
    void hashUpgradeIsACompareAndSetThatNeverOverwritesANewerPassword() {
        String legacy = new BCryptPasswordEncoder(10).encode(PASSWORD);
        Account account = createAccountWithHash(legacy, false);
        String newer = passwordEncoder.encode(NEW_PASSWORD);
        jdbc.update("UPDATE account SET password = ? WHERE id = ?", newer, account.getId());

        Boolean staleWrite = transactions.execute(status ->
                accountLocks.compareAndSetPassword(account.getId(), legacy, passwordEncoder.encode(PASSWORD), false));

        assertThat(staleWrite).isFalse();
        assertThat(jdbc.queryForObject("SELECT password FROM account WHERE id = ?", String.class, account.getId()))
                .isEqualTo(newer);
        // A login that verified the legacy hash just before the change cannot resurrect the old password.
        assertThatThrownBy(() -> login(account, PASSWORD)).isInstanceOf(AuthProblemException.class);
        assertThat(login(account, NEW_PASSWORD).refreshToken()).isNotBlank();
    }

    @Test
    void failedRevocationRollsBackThePasswordChange() throws Exception {
        Account account = createAccount(true);
        String existingSession = login(account, PASSWORD).refreshToken();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_block_revoke_" + suffix;
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS "
                + "$$ BEGIN RAISE EXCEPTION 'revocation blocked by test'; END $$");
        jdbc.execute("CREATE TRIGGER " + function + " BEFORE UPDATE ON refresh_token FOR EACH ROW WHEN "
                + "(OLD.account_id = " + account.getId() + ") EXECUTE FUNCTION " + function + "()");
        try {
            assertThatThrownBy(() -> authService.changePassword(AuthenticatedUser.of(account),
                    new PasswordChangeRequest(PASSWORD, NEW_PASSWORD), client()))
                    .isNotInstanceOf(AuthProblemException.class);
        } finally {
            jdbc.execute("DROP TRIGGER " + function + " ON refresh_token");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }

        Account stored = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(passwordEncoder.matches(PASSWORD, stored.getPassword())).isTrue();
        assertThat(stored.isMustChangePassword()).isTrue();
        assertThat(countEvents("PASSWORD_CHANGED", account.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token_family WHERE account_id = ? "
                + "AND revoked_at IS NULL", Long.class, account.getId())).isEqualTo(1);
        assertThat(authService.refresh(existingSession, client()).refreshToken()).isNotBlank();
    }
}
