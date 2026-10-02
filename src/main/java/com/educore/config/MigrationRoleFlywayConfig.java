package com.educore.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Least privilege at runtime (AC-15): when {@code educore.database.migration-username} is set
 * ({@code EDUCORE_DB_MIGRATION_USERNAME}), Flyway migrates the schema as that owner role over the application's
 * JDBC URL, while the application's own pool keeps the runtime role of {@code spring.datasource.*}, which has
 * DML privileges only ({@code infra/postgres/app-role.sql}). Without it (local runs, tests) Flyway uses the data
 * source as before. The customizer runs after Spring Boot configured Flyway, so it replaces the data source.
 */
@Configuration(proxyBeanMethods = false)
public class MigrationRoleFlywayConfig {

    @Bean
    FlywayConfigurationCustomizer migrationRoleFlywayCustomizer(EduCoreProperties properties,
                                                               JdbcConnectionDetails connection) {
        EduCoreProperties.Database database = properties.database();
        return configuration -> {
            if (database.hasMigrationRole()) {
                configuration.dataSource(connection.getJdbcUrl(), database.migrationUsername(),
                        database.migrationPassword());
            }
        };
    }
}
