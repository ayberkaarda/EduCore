package com.educore.support;

import com.educore.EduCoreApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
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

    private final String jdbcUrl;

    private ScratchDatabase(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
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
                + AbstractIntegrationTest.POSTGRES.getMappedPort(5432) + "/" + name);
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

    /** Starts the full application (random ports) against this database with the given profile(s). */
    public ConfigurableApplicationContext start(String profiles, String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=" + profiles,
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + AbstractIntegrationTest.POSTGRES.getUsername(),
                "--spring.datasource.password=" + AbstractIntegrationTest.POSTGRES.getPassword(),
                "--server.port=0",
                "--management.server.port=0",
                "--educore.ingestion.base-dir=target/test-csv-uploads"));
        if (profiles.contains("prod")) {
            args.add("--educore.security.jwt.secret=" + TEST_JWT_SECRET);
            args.add("--educore.security.login.username-pepper=scratch-test-only-login-pepper-not-secret");
            args.add("--educore.bootstrap.admin.username=scratch-admin");
            args.add("--educore.bootstrap.admin.password=scratch-test-only-value");
        }
        args.addAll(List.of(extraArgs));
        return new SpringApplicationBuilder(EduCoreApplication.class).logStartupInfo(false)
                .run(args.toArray(String[]::new));
    }
}
