package com.educore;

import com.educore.support.ScratchDatabase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The one-time upgrade of a database that was migrated before P5/P7 (docs/ops/UPGRADE.md). The new migrations
 * V12 (rename ip_block -> ip_allocation_range plus ip_deny_rule), V13 (unique AUTO deny index) and V21 (account
 * lifecycle: {@code deleted} flag -> {@code status}) sort below the already applied V30..V42, so Flyway refuses
 * them unless {@code SPRING_FLYWAY_OUT_OF_ORDER=true} is set for that one start. The scratch database is first
 * migrated with every migration except V12..V19 and V21 (the previously released set) and holds an
 * {@code ip_block} row plus one active and one soft-deleted account.
 */
class FlywayOutOfOrderUpgradeIT {

    /**
     * Migrations added after V42 was released (P5: V12..V19, P7: V21, security fixes: V22 session epoch, V23 login
     * attempt client key, V33 erasure ledger, V34 upload staging); everything else was released before.
     */
    private static final Pattern NEW_MIGRATION = Pattern.compile("V(1[2-9]|2[1-9]|3[3-9])__.*\\.sql");

    @TempDir
    Path preP5Migrations;

    private ScratchDatabase preP5Database() throws Exception {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql");
        int copied = 0;
        for (Resource script : scripts) {
            String name = script.getFilename();
            if (name != null && !NEW_MIGRATION.matcher(name).matches()) {
                try (InputStream in = script.getInputStream()) {
                    Files.copy(in, preP5Migrations.resolve(name));
                }
                copied++;
            }
        }
        assertThat(copied).as("released migrations V1..V11, V20, V30..V42").isGreaterThanOrEqualTo(12);
        ScratchDatabase db = ScratchDatabase.create();
        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("filesystem:" + preP5Migrations.toAbsolutePath(), "classpath:db/seed/dev")
                .load()
                .migrate();
        db.execute("INSERT INTO ip_block (type, original_value, start_ip, end_ip) "
                + "VALUES ('CIDR', '198.51.100.0/24', 3325256704, 3325256959)");
        db.execute("INSERT INTO account (username, password, first_name, role, deleted) VALUES "
                + "('upgrade-active', 'upgrade-test-hash', 'Active', 'USER', 0), "
                + "('upgrade-removed', 'upgrade-test-hash', 'Removed', 'USER', 1)");
        return db;
    }

    /** Starts the application the way the container does: settings from (OS) environment variables. */
    private static ConfigurableApplicationContext start(ScratchDatabase db, Map<String, Object> extraEnvironment) {
        Map<String, Object> env = new HashMap<>(extraEnvironment);
        env.put("SPRING_PROFILES_ACTIVE", "test");
        StandardEnvironment environment = new StandardEnvironment() {
            @Override
            protected void customizePropertySources(MutablePropertySources sources) {
                super.customizePropertySources(sources);
                sources.addFirst(new SystemEnvironmentPropertySource("upgrade-systemEnvironment", env));
                sources.addFirst(new MapPropertySource("upgrade-test-connection", Map.of(
                        "spring.datasource.url", db.jdbcUrl(),
                        "spring.datasource.username", db.username(),
                        "spring.datasource.password", db.password(),
                        "server.port", "0",
                        "management.server.port", "0",
                        "educore.ingestion.base-dir", "target/test-csv-uploads")));
            }
        };
        return new SpringApplicationBuilder(EduCoreApplication.class).environment(environment)
                .logStartupInfo(false).run();
    }

    @Test
    void defaultSettingsRefuseToStartWithAClearFlywayMessage() throws Exception {
        ScratchDatabase db = preP5Database();

        Throwable failure = catchThrowable(() -> start(db, Map.of()).close());

        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root.getMessage()).contains("Detected resolved migration not applied to database: 12")
                .contains("Detected resolved migration not applied to database: 13")
                .contains("Detected resolved migration not applied to database: 21")
                .contains("Detected resolved migration not applied to database: 22")
                .contains("Detected resolved migration not applied to database: 23")
                .contains("Detected resolved migration not applied to database: 33")
                .contains("Detected resolved migration not applied to database: 34");
        // Nothing was changed: the old table, its row and the old account flag are untouched.
        assertThat(count(db, "SELECT count(*) FROM ip_block")).isOne();
        assertThat(count(db, "SELECT count(*) FROM account WHERE username LIKE 'upgrade-%' AND deleted = 1"))
                .isOne();
    }

    @Test
    void outOfOrderOnceAppliesP5AndKeepsTheData() throws Exception {
        ScratchDatabase db = preP5Database();
        long oldId = count(db, "SELECT id FROM ip_block");

        try (ConfigurableApplicationContext context = start(db, Map.of("SPRING_FLYWAY_OUT_OF_ORDER", "true"))) {
            // The context started, so Hibernate's ddl-auto=validate accepted the upgraded schema.
            Flyway flyway = context.getBean(Flyway.class);
            List<String> outOfOrder = Arrays.stream(flyway.info().applied())
                    .filter(info -> info.getState() == MigrationState.OUT_OF_ORDER)
                    .map(MigrationInfo::getVersion).map(Object::toString).toList();
            assertThat(outOfOrder).containsExactly("12", "13", "21", "22", "23", "33", "34");
            assertThat(flyway.info().pending()).isEmpty();

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT original_value FROM ip_allocation_range WHERE id = ?",
                    String.class, oldId)).isEqualTo("198.51.100.0/24");
            Long newId = jdbc.queryForObject("INSERT INTO ip_allocation_range (type, original_value, start_ip, end_ip) "
                    + "VALUES ('STATIC', '198.51.101.1', 3325257985, 3325257985) RETURNING id", Long.class);
            assertThat(newId).isGreaterThan(oldId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_name IN ('ip_block')", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ip_deny_rule", Long.class)).isZero();

            // V21 migrated the existing rows: deleted = 0 -> ACTIVE, deleted = 1 -> DEACTIVATED; the flag is gone.
            assertThat(jdbc.queryForObject("SELECT status FROM account WHERE username = 'upgrade-active'",
                    String.class)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT status FROM account WHERE username = 'upgrade-removed'",
                    String.class)).isEqualTo("DEACTIVATED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_name = 'account' AND column_name = 'deleted'", Long.class)).isZero();
        }

        // Later starts (variable removed again) validate cleanly: out-of-order is needed exactly once.
        try (ConfigurableApplicationContext context = start(db, Map.of())) {
            assertThat(context.getBean(Flyway.class).info().pending()).isEmpty();
        }
    }

    private static long count(ScratchDatabase db, String sql) throws Exception {
        try (Connection connection = db.connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
