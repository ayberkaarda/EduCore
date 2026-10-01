package com.educore.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests: full Spring context under the {@code test} profile against a real
 * PostgreSQL started by Testcontainers.
 * <p>
 * The container is a JVM-wide singleton (started once in the static initializer, removed by the
 * Testcontainers resource reaper when the JVM exits), so every cached Spring context shares it.
 * It listens on a random host port; no fixed port is bound.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("educore_test")
            .withUsername("educore_test");

    static {
        POSTGRES.start();
    }
}
