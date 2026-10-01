package com.educore.config;

import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bootstrap ADMIN is created only when no ADMIN exists. Each test runs in a rolled-back transaction,
 * so the seeded data seen by other integration tests is unchanged.
 */
@Transactional
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "educore.bootstrap.admin.username=" + AdminBootstrapIT.BOOTSTRAP_USERNAME,
        "educore.bootstrap.admin.password=" + AdminBootstrapIT.BOOTSTRAP_PASSWORD
})
class AdminBootstrapIT extends AbstractIntegrationTest {

    static final String BOOTSTRAP_USERNAME = "bootstrap-admin";
    /** Test data only: never used outside this test. */
    static final String BOOTSTRAP_PASSWORD = "test-only-bootstrap-password";

    @Autowired
    private AdminBootstrap adminBootstrap;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void doesNothingWhenAnAdminAlreadyExists() {
        // The test seed contains an ADMIN, so the startup run must not have created another one.
        assertThat(accountRepository.existsByRole(Role.ADMIN)).isTrue();
        assertThat(accountRepository.findByUsername(BOOTSTRAP_USERNAME)).isEmpty();

        adminBootstrap.run(new DefaultApplicationArguments());

        assertThat(accountRepository.findByUsername(BOOTSTRAP_USERNAME)).isEmpty();
    }

    @Test
    void createsAdminWithEncodedPasswordWhenNoAdminExists(CapturedOutput output) {
        accountRepository.findAll().stream()
                .filter(account -> account.getRole() == Role.ADMIN)
                .forEach(account -> account.setRole(Role.USER));
        accountRepository.flush();
        assertThat(accountRepository.existsByRole(Role.ADMIN)).isFalse();

        adminBootstrap.run(new DefaultApplicationArguments());

        Account admin = accountRepository.findByUsername(BOOTSTRAP_USERNAME).orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(admin.getPassword()).isNotEqualTo(BOOTSTRAP_PASSWORD);
        assertThat(passwordEncoder.matches(BOOTSTRAP_PASSWORD, admin.getPassword())).isTrue();
        assertThat(output).contains("ADMIN_BOOTSTRAPPED").doesNotContain(BOOTSTRAP_PASSWORD);
    }
}
