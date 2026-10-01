package com.educore;

import com.educore.support.ScratchDatabase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Databases created before Flyway (no schema history) are adopted through baseline-on-migrate at version 1:
 * V2 adds whatever Spring Batch tables are missing, V4 and the seed run, and existing rows survive.
 */
class FlywayLegacyAdoptionIT {

    private static final List<String> BATCH_TABLES = List.of(
            "batch_job_instance", "batch_job_execution", "batch_job_execution_params",
            "batch_step_execution", "batch_step_execution_context", "batch_job_execution_context");

    private static ScratchDatabase legacyDatabase(boolean withBatchTables) throws Exception {
        ScratchDatabase db = ScratchDatabase.create();
        db.executeClasspathScript("db/migration/V1__baseline.sql");
        if (withBatchTables) {
            db.executeClasspathScript("db/migration/V2__spring_batch_schema.sql");
        }
        db.execute("INSERT INTO account (username, password, first_name, student_number, role, deleted) "
                + "VALUES ('legacy-user', 'legacy-hash', 'Legacy', '9000099', 'USER', 0)");
        return db;
    }

    private static void assertAdopted(ConfigurableApplicationContext context) {
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        Flyway flyway = context.getBean(Flyway.class);

        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).containsAll(BATCH_TABLES);

        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied[0].getState()).isEqualTo(MigrationState.BASELINE);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        List<String> migrated = Arrays.stream(applied).skip(1)
                .map(info -> info.getVersion() == null ? "R:" + info.getDescription() : info.getVersion().getVersion())
                .toList();
        assertThat(migrated).containsExactly("2", "4", "10", "11", "20", "R:dev seed");
        assertThat(applied).allSatisfy(info -> assertThat(info.getState())
                .isIn(MigrationState.BASELINE, MigrationState.SUCCESS));
        assertThat(flyway.info().pending()).isEmpty();
        assertThatCode(flyway::validate).doesNotThrowAnyException();

        Boolean legacyFlag = jdbc.queryForObject(
                "SELECT must_change_password FROM account WHERE username = 'legacy-user'", Boolean.class);
        assertThat(legacyFlag).isFalse();
    }

    @Test
    void adoptsLegacyDatabaseWithoutBatchTables() throws Exception {
        ScratchDatabase db = legacyDatabase(false);

        try (ConfigurableApplicationContext context = db.start("test")) {
            assertAdopted(context);
        }
    }

    @Test
    void adoptsLegacyDatabaseThatAlreadyHasBatchTables() throws Exception {
        ScratchDatabase db = legacyDatabase(true);

        try (ConfigurableApplicationContext context = db.start("test")) {
            assertAdopted(context);
        }
    }
}
