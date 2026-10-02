package com.educore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refuses to start the {@code prod} profile on a database that still holds the development seed accounts (R-02,
 * AC-03). A database first used under {@code dev} keeps the rows of {@code db/seed/dev/R__dev_seed.sql} after the
 * switch to {@code prod} (the Flyway location check of {@link ProdStartupGuard} only keeps the seed from running
 * again), and {@link AdminBootstrap} creates nothing while an ADMIN exists, so the demo administrator, whose
 * password is public, would stay the only administrator.
 * <p>
 * An account carries the dev-seed identity when its username is a seeded username AND it still has the seeded
 * password hash (with or without the {@code {bcrypt}} prefix) or the seeded student number from the reserved
 * synthetic range. The identities are read from the seed script shipped with this build, so they can never drift
 * from it. The check runs while the context starts, after Flyway (it needs the {@link JdbcTemplate}) and before
 * the web server accepts a request. The message names the usernames and the remedy, never a hash.
 */
@Component
@Profile("prod")
public class DevSeedAccountGuard {

    static final String SEED_SCRIPT = "db/seed/dev/R__dev_seed.sql";
    private static final Logger log = LoggerFactory.getLogger(DevSeedAccountGuard.class);
    /** {@code ('username', '<bcrypt hash>', 'first', 'last', 'student number', 'ROLE')} rows of the seed. */
    private static final Pattern SEED_ACCOUNT = Pattern.compile(
            "\\(\\s*'([^']+)'\\s*,\\s*'(\\$2[aby]?\\$\\d{2}\\$[./A-Za-z0-9]{53})'\\s*,\\s*'[^']*'\\s*,\\s*'[^']*'\\s*,"
                    + "\\s*'([^']*)'\\s*,\\s*'(?:ADMIN|USER)'\\s*\\)");

    /** One seeded account: username, password hash and student number. */
    record SeedIdentity(String username, String passwordHash, String studentNumber) {

        @Override
        public String toString() {
            return "SeedIdentity[username=" + username + "]";
        }
    }

    public DevSeedAccountGuard(JdbcTemplate jdbc) {
        List<String> found = new ArrayList<>();
        for (SeedIdentity identity : seedIdentities()) {
            Integer matches = jdbc.queryForObject("SELECT count(*) FROM account WHERE username = ? "
                            + "AND (password = ? OR password = ? OR student_number = ?)", Integer.class,
                    identity.username(), identity.passwordHash(), "{bcrypt}" + identity.passwordHash(),
                    identity.studentNumber());
            if (matches != null && matches > 0) {
                found.add(identity.username());
            }
        }
        if (!found.isEmpty()) {
            throw new IllegalStateException("Refusing to start with profile 'prod': the database contains the "
                    + "development seed account(s) " + String.join(", ", found) + " from " + SEED_SCRIPT
                    + " (their demo password is public). Remove them before using this database in production: "
                    + "docs/ops/UPGRADE.md, section \"Promoting a database that was used under dev\".");
        }
    }

    /** The seeded accounts, parsed from the seed script on the classpath (empty when the script is absent). */
    static List<SeedIdentity> seedIdentities() {
        ClassPathResource script = new ClassPathResource(SEED_SCRIPT);
        if (!script.exists()) {
            log.warn("{} is not on the classpath; the development seed account check is skipped", SEED_SCRIPT);
            return List.of();
        }
        String sql;
        try {
            sql = script.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<SeedIdentity> identities = new ArrayList<>();
        Matcher matcher = SEED_ACCOUNT.matcher(sql);
        while (matcher.find()) {
            identities.add(new SeedIdentity(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        return List.copyOf(identities);
    }
}
