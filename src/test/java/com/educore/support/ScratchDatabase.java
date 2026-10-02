package com.educore.support;

import com.educore.EduCoreApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.Container;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A throwaway database inside the shared Testcontainers PostgreSQL, for tests that must control the
 * starting schema (legacy adoption, profile switches) and start the application themselves.
 */
public final class ScratchDatabase {

    /** TEST DATA ONLY: base64 of the ASCII text "educore-test-only-jwt-signing-secret-never-use-outside-tests". */
    private static final String TEST_JWT_SECRET =
            "ZWR1Y29yZS10ZXN0LW9ubHktand0LXNpZ25pbmctc2VjcmV0LW5ldmVyLXVzZS1vdXRzaWRlLXRlc3Rz"; // gitleaks:allow

    /** TEST DATA ONLY: base64 of the ASCII text "educore-test-only-encryption-key" (32 bytes). */
    private static final String TEST_ENCRYPTION_KEY = "ZWR1Y29yZS10ZXN0LW9ubHktZW5jcnlwdGlvbi1rZXk="; // gitleaks:allow

    private final String jdbcUrl;
    private final String name;

    private ScratchDatabase(String jdbcUrl, String name) {
        this.jdbcUrl = jdbcUrl;
        this.name = name;
    }

    public static ScratchDatabase create() {
        String name = "scratch_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(AbstractIntegrationTest.POSTGRES.getJdbcUrl(),
                AbstractIntegrationTest.POSTGRES.getUsername(), AbstractIntegrationTest.POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create scratch database " + name, e);
        }
        return new ScratchDatabase("jdbc:postgresql://" + AbstractIntegrationTest.POSTGRES.getHost() + ":"
                + AbstractIntegrationTest.POSTGRES.getMappedPort(5432) + "/" + name, name);
    }

    public String name() {
        return name;
    }

    /** Runs a command (pg_dump, pg_restore, psql) inside the shared PostgreSQL container. */
    public Container.ExecResult exec(String... command) throws IOException, InterruptedException {
        return AbstractIntegrationTest.POSTGRES.execInContainer(command);
    }

    /** The least-privilege runtime role of this database (created by {@link #createRuntimeRole()}). */
    public String runtimeRole() {
        return name + "_app";
    }

    /**
     * Creates or refreshes the runtime role with the deployment's own script {@code infra/postgres/app-role.sql},
     * run by psql inside the database container exactly as {@code infra/postgres/init/01-roles.sh} runs it; the
     * scratch database's owner is the container's (only) superuser. Returns the role's password.
     */
    public String createRuntimeRole() {
        String password = "scratch-runtime-" + UUID.randomUUID();
        try {
            AbstractIntegrationTest.POSTGRES.copyFileToContainer(
                    MountableFile.forHostPath(Path.of("infra/postgres/app-role.sql")), "/tmp/educore-app-role.sql");
            Container.ExecResult result = AbstractIntegrationTest.POSTGRES.execInContainer("sh", "-c",
                    "EDUCORE_DB_APP_PASSWORD=\"$1\" psql -X -q -v ON_ERROR_STOP=1 -U \"$2\" -d \"$3\" "
                            + "-v app_user=\"$4\" -v owner=\"$2\" -v db=\"$3\" -f /tmp/educore-app-role.sql",
                    "sh", password, username(), name, runtimeRole());
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("app-role.sql failed: " + result.getStderr());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return password;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String username() {
        return AbstractIntegrationTest.POSTGRES.getUsername();
    }

    public String password() {
        return AbstractIntegrationTest.POSTGRES.getPassword();
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, AbstractIntegrationTest.POSTGRES.getUsername(),
                AbstractIntegrationTest.POSTGRES.getPassword());
    }

    /** Runs SQL statements directly, bypassing Flyway (no schema history is written). */
    public void execute(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    public void executeClasspathScript(String location) throws SQLException, IOException {
        execute(new ClassPathResource(location).getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * Starts the full application (random ports) against this database with the given profile(s). Under
     * {@code prod} the deployment's role split applies: Flyway migrates as the owner, the application connects as
     * the least-privilege runtime role ({@link #createRuntimeRole()}), and the erasure ledger file is set.
     */
    public ConfigurableApplicationContext start(String profiles, String... extraArgs) {
        boolean prod = profiles.contains("prod");
        String runtimePassword = prod ? createRuntimeRole() : null;
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=" + profiles,
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + (prod ? runtimeRole() : AbstractIntegrationTest.POSTGRES.getUsername()),
                "--spring.datasource.password=" + (prod ? runtimePassword : AbstractIntegrationTest.POSTGRES.getPassword()),
                "--server.port=0",
                "--management.server.port=0",
                "--educore.ingestion.base-dir=target/test-csv-uploads"));
        if (prod) {
            args.add("--educore.database.migration-username=" + AbstractIntegrationTest.POSTGRES.getUsername());
            args.add("--educore.database.migration-password=" + AbstractIntegrationTest.POSTGRES.getPassword());
            args.add("--educore.lifecycle.erasure-ledger-file=target/test-erasure-ledger/" + name + ".log");
            args.add("--educore.security.jwt.secret=" + TEST_JWT_SECRET);
            args.add("--educore.security.login.username-pepper=scratch-test-only-login-pepper-not-secret");
            args.add("--educore.crypto.encryption-key=" + TEST_ENCRYPTION_KEY);
            args.add("--educore.bootstrap.admin.username=scratch-admin");
            args.add("--educore.bootstrap.admin.password=scratch-test-only-value");
            args.add("--educore.seo.base-url=https://scratch.example.org");
        }
        args.addAll(List.of(extraArgs));
        return new SpringApplicationBuilder(EduCoreApplication.class).logStartupInfo(false)
                .run(args.toArray(String[]::new));
    }
}
