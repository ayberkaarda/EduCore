package com.educore;

import com.educore.support.ScratchDatabase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A database first migrated under dev/test (with the repeatable dev seed in its history) can later be started
 * under prod once the seed accounts are removed: prod's Flyway locations do not contain the seed,
 * ignore-migration-patterns tolerates exactly that missing repeatable, and {@code DevSeedAccountGuard} refuses the
 * seed accounts themselves.
 */
class FlywayProfileSwitchIT {

    private static ScratchDatabase devSeededDatabase() {
        ScratchDatabase db = ScratchDatabase.create();
        try (ConfigurableApplicationContext dev = db.start("test")) {
            boolean seedApplied = Arrays.stream(dev.getBean(Flyway.class).info().applied())
                    .anyMatch(info -> info.getVersion() == null && "dev seed".equals(info.getDescription()));
            assertThat(seedApplied).isTrue();
        }
        return db;
    }

    /**
     * R-02 / AC-03: prod refuses a database that still holds the dev seed accounts (public demo password) and names
     * the remedy; once they are removed (docs/ops/UPGRADE.md), prod starts on the same database.
     */
    @Test
    void prodRefusesTheDevSeedAccountsAndStartsOnceTheyAreRemoved() throws Exception {
        ScratchDatabase db = devSeededDatabase();

        assertThatThrownBy(() -> db.start("prod").close())
                .rootCause()
                .hasMessageContaining("development seed account(s) admin, ayberk, ali")
                .hasMessageContaining("docs/ops/UPGRADE.md")
                .hasMessageNotContaining("$2a$");

        // A seed account whose password was re-hashed on login is still recognised by its student number.
        db.execute("UPDATE account SET password = '{bcrypt}rehashed-on-login' WHERE username = 'ali'");
        assertThatThrownBy(() -> db.start("prod").close()).rootCause()
                .hasMessageContaining("development seed account(s) admin, ayberk, ali");

        db.execute("DELETE FROM enrollments WHERE account_id IN (SELECT id FROM account "
                + "WHERE username IN ('admin', 'ayberk', 'ali'))");
        db.execute("DELETE FROM account WHERE username IN ('admin', 'ayberk', 'ali')");
        try (ConfigurableApplicationContext prod = db.start("prod")) {
            Flyway flyway = prod.getBean(Flyway.class);
            assertThat(flyway.info().pending()).isEmpty();
            assertThat(Arrays.stream(flyway.info().applied()).map(MigrationInfo::getDescription))
                    .contains("dev seed");
        }
    }

    @Test
    void withoutTheIgnorePatternProdValidationRejectsTheMissingRepeatable() {
        ScratchDatabase db = devSeededDatabase();

        assertThatThrownBy(() -> db.start("prod", "--spring.flyway.ignore-migration-patterns=*:future").close())
                .hasStackTraceContaining("dev seed");
    }
}
