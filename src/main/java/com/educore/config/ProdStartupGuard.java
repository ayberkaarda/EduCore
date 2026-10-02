package com.educore.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Refuses to start the {@code prod} profile when a required setting is missing, when {@code dev} or
 * {@code test} is active at the same time, or when Flyway would load a seed (demo data) location.
 * <p>
 * Runs as an {@link EnvironmentPostProcessor} after the profile-specific configuration files are loaded,
 * so the check happens before any bean (data source, JWT key, bootstrap ADMIN) is created and needs no
 * database. {@code EDUCORE_SEO_BASE_URL} must also be an {@code https} origin that is not localhost, and the
 * runtime database role must differ from the migration (owner) role. The failure message names the environment
 * variables only, never their values.
 * Registered in {@code META-INF/spring.factories}.
 */
public class ProdStartupGuard implements EnvironmentPostProcessor, Ordered {

    static final String PROD_PROFILE = "prod";

    /** Profiles that load demo data or test-only settings and must never be combined with prod. */
    static final List<String> FORBIDDEN_WITH_PROD = List.of("dev", "test");

    /** Any Flyway location containing this path segment carries demo data. */
    static final String SEED_LOCATION_MARKER = "db/seed";

    static final String SEO_BASE_URL_PROPERTY = "educore.seo.base-url";

    static final String DATASOURCE_USERNAME_PROPERTY = "spring.datasource.username";

    static final String MIGRATION_USERNAME_PROPERTY = "educore.database.migration-username";

    /** Host names that can never be the public origin of a production site. */
    private static final List<String> LOCAL_HOSTS = List.of("localhost", "127.0.0.1", "[::1]", "::1", "0.0.0.0");

    /** Environment variable name mapped to the property it feeds. */
    static final Map<String, String> REQUIRED_IN_PROD = requiredInProd();

    private static Map<String, String> requiredInProd() {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("EDUCORE_DB_URL", "spring.datasource.url");
        required.put("EDUCORE_DB_APP_USERNAME", DATASOURCE_USERNAME_PROPERTY);
        required.put("EDUCORE_DB_APP_PASSWORD", "spring.datasource.password");
        required.put("EDUCORE_DB_MIGRATION_USERNAME", MIGRATION_USERNAME_PROPERTY);
        required.put("EDUCORE_DB_MIGRATION_PASSWORD", "educore.database.migration-password");
        required.put("EDUCORE_ERASURE_LEDGER_FILE", "educore.lifecycle.erasure-ledger-file");
        required.put("EDUCORE_JWT_SECRET", "educore.security.jwt.secret");
        required.put("EDUCORE_LOGIN_PEPPER", "educore.security.login.username-pepper");
        required.put("EDUCORE_ENCRYPTION_KEY", "educore.crypto.encryption-key");
        required.put("EDUCORE_BOOTSTRAP_ADMIN_USERNAME", "educore.bootstrap.admin.username");
        required.put("EDUCORE_BOOTSTRAP_ADMIN_PASSWORD", "educore.bootstrap.admin.password");
        required.put("EDUCORE_SEO_BASE_URL", SEO_BASE_URL_PROPERTY);
        return Collections.unmodifiableMap(required);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.matchesProfiles(PROD_PROFILE)) {
            return;
        }
        for (String forbidden : FORBIDDEN_WITH_PROD) {
            if (environment.matchesProfiles(forbidden)) {
                throw new IllegalStateException("Refusing to start with profile 'prod': profile '" + forbidden
                        + "' must not be active at the same time (check SPRING_PROFILES_ACTIVE).");
            }
        }
        String flywayLocations = safeGet(environment, "spring.flyway.locations");
        if (flywayLocations != null && flywayLocations.contains(SEED_LOCATION_MARKER)) {
            throw new IllegalStateException("Refusing to start with profile 'prod': spring.flyway.locations "
                    + "includes a seed location (" + SEED_LOCATION_MARKER + "); demo data must never reach prod.");
        }
        List<String> missing = new ArrayList<>();
        REQUIRED_IN_PROD.forEach((variable, property) -> {
            if (!hasValue(environment, property)) {
                missing.add(variable);
            }
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Refusing to start with profile 'prod': required environment "
                    + "variable(s) not set: " + String.join(", ", missing)
                    + ". See .env.example for the meaning of each variable.");
        }
        requirePublicHttpsOrigin(safeGet(environment, SEO_BASE_URL_PROPERTY));
        requireDistinctDatabaseRoles(safeGet(environment, DATASOURCE_USERNAME_PROPERTY),
                safeGet(environment, MIGRATION_USERNAME_PROPERTY));
    }

    /**
     * The runtime role must not be the migration (owner) role: only then is the application limited to DML and
     * unable to drop tables, create extensions or run {@code COPY ... PROGRAM} (AC-15, infra/postgres/app-role.sql).
     * The message names the variables, not their values.
     */
    static void requireDistinctDatabaseRoles(String runtimeUsername, String migrationUsername) {
        if (runtimeUsername.trim().equalsIgnoreCase(migrationUsername.trim())) {
            throw new IllegalStateException("Refusing to start with profile 'prod': EDUCORE_DB_APP_USERNAME (the "
                    + "runtime role) must differ from EDUCORE_DB_MIGRATION_USERNAME (the schema owner Flyway "
                    + "migrates with); see docs/ops/UPGRADE.md, section \"Least-privilege database role\".");
        }
    }

    /**
     * {@code EDUCORE_SEO_BASE_URL} is the allowed {@code Origin} of refresh/logout and the prefix of every sitemap
     * URL: in prod it must be an {@code https} origin of a real host, never the {@code http://localhost:3000}
     * development default. The message names the variable, not its value.
     */
    static void requirePublicHttpsOrigin(String value) {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Refusing to start with profile 'prod': EDUCORE_SEO_BASE_URL is not a "
                    + "valid URL.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host.isEmpty() || LOCAL_HOSTS.contains(host)
                || host.endsWith(".localhost") || host.startsWith("127.")) {
            throw new IllegalStateException("Refusing to start with profile 'prod': EDUCORE_SEO_BASE_URL must be "
                    + "the public https origin of the site (e.g. https://educore.example.org), not an http or "
                    + "localhost address.");
        }
    }

    private static boolean hasValue(ConfigurableEnvironment environment, String property) {
        String value = safeGet(environment, property);
        return value != null && !value.isBlank();
    }

    private static String safeGet(ConfigurableEnvironment environment, String property) {
        try {
            return environment.getProperty(property);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            // application-prod.yml references the variable without a default; unresolved means unset.
            return null;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
