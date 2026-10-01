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
 * under prod: prod's Flyway locations do not contain the seed, and ignore-migration-patterns tolerates exactly
 * that missing repeatable.
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

    @Test
    void prodStartsOnDatabaseSeededUnderDev() {
        ScratchDatabase db = devSeededDatabase();

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
