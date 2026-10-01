package com.educore.service;

import com.educore.auth.PasswordPolicy;
import com.educore.entity.Account;
import com.educore.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Temporary passwords for API-created and CSV-imported accounts. */
class AccountCredentialServiceTest {

    private static final PasswordEncoder ENCODER = new SecurityConfig().passwordEncoder();
    private static final PasswordPolicy POLICY = new PasswordPolicy();

    private final AccountCredentialService service = new AccountCredentialService(ENCODER, POLICY);

    @Test
    void generatedPasswordsHave24CharactersFromTheAlphabetAndEveryClass() {
        for (int i = 0; i < 200; i++) {
            String password = service.generateTemporaryPassword();

            assertThat(password).hasSize(AccountCredentialService.TEMPORARY_PASSWORD_LENGTH);
            assertThat(password.chars()).allMatch(c -> AccountCredentialService.ALPHABET.indexOf(c) >= 0);
            assertThat(password).containsAnyOf(chars(AccountCredentialService.UPPER));
            assertThat(password).containsAnyOf(chars(AccountCredentialService.LOWER));
            assertThat(password).containsAnyOf(chars(AccountCredentialService.DIGITS));
            assertThat(password).containsAnyOf(chars(AccountCredentialService.SYMBOLS));
        }
    }

    @Test
    void generatedPasswordsSatisfyThePasswordPolicy() {
        for (int i = 0; i < 200; i++) {
            assertThat(POLICY.violations(service.generateTemporaryPassword())).isEmpty();
        }
    }

    @Test
    void generatedPasswordsDifferAcrossCalls() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            seen.add(service.generateTemporaryPassword());
        }
        assertThat(seen).hasSize(1_000);
    }

    @Test
    void assignStoresOnlyTheHashAndRequiresAPasswordChange() {
        Account account = new Account();

        String temporaryPassword = service.assignTemporaryPassword(account);

        assertThat(account.getPassword()).isNotEqualTo(temporaryPassword).doesNotContain(temporaryPassword)
                .startsWith("{bcrypt}$2a$12$");
        assertThat(ENCODER.matches(temporaryPassword, account.getPassword())).isTrue();
        assertThat(account.isMustChangePassword()).isTrue();
    }

    @Test
    void eachAssignmentProducesADifferentPasswordAndHash() {
        Account first = new Account();
        Account second = new Account();

        String firstPassword = service.assignTemporaryPassword(first);
        String secondPassword = service.assignTemporaryPassword(second);

        assertThat(firstPassword).isNotEqualTo(secondPassword);
        assertThat(first.getPassword()).isNotEqualTo(second.getPassword());
        assertThat(ENCODER.matches(firstPassword, second.getPassword())).isFalse();
    }

    private static String[] chars(String alphabet) {
        return alphabet.chars().mapToObj(c -> String.valueOf((char) c)).toArray(String[]::new);
    }
}
