package com.educore.config;

import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first ADMIN account from {@code EDUCORE_BOOTSTRAP_ADMIN_USERNAME} /
 * {@code EDUCORE_BOOTSTRAP_ADMIN_PASSWORD} when no ADMIN exists yet.
 * <p>
 * Does nothing when the variables are not set (they are mandatory only in {@code prod},
 * enforced by {@link ProdStartupGuard}) or when an ADMIN already exists. The password is stored
 * through the application's {@link PasswordEncoder} and the account must change it on first use.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final EduCoreProperties properties;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(EduCoreProperties properties, AccountRepository accountRepository,
                          PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        EduCoreProperties.Admin admin = properties.bootstrap().admin();
        if (!admin.isConfigured() || accountRepository.existsByRole(Role.ADMIN)) {
            return;
        }
        String username = admin.username().trim();
        if (accountRepository.findByUsername(username).isPresent()) {
            throw new IllegalStateException("Cannot bootstrap ADMIN: EDUCORE_BOOTSTRAP_ADMIN_USERNAME names an "
                    + "existing non-admin account; choose a different username.");
        }
        accountRepository.save(Account.builder()
                .username(username)
                .password(passwordEncoder.encode(admin.password()))
                // The login response includes firstName, so the bootstrap account needs one.
                .firstName("Administrator")
                .role(Role.ADMIN)
                .deleted(0)
                .mustChangePassword(true)
                .build());
        log.info("ADMIN_BOOTSTRAPPED username={}", username);
    }
}
