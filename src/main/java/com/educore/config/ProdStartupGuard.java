package com.educore.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Refuses to start the {@code prod} profile when a required setting is missing, when {@code dev} or
 * {@code test} is active at the same time, or when Flyway would load a seed (demo data) location.
 * <p>
 * Runs as an {@link EnvironmentPostProcessor} after the profile-specific configuration files are loaded,
 * so the check happens before any bean (data source, JWT key, bootstrap ADMIN) is created and needs no
 * database. The failure message names the environment variables only, never their values.
 * Registered in {@code META-INF/spring.factories}.
 */
public class ProdStartupGuard implements EnvironmentPostProcessor, Ordered {

    static final String PROD_PROFILE = "prod";

    /** Profiles that load demo data or test-only settings and must never be combined with prod. */
    static final List<String> FORBIDDEN_WITH_PROD = List.of("dev", "test");

    /** Any Flyway location containing this path segment carries demo data. */
    static final String SEED_LOCATION_MARKER = "db/seed";

    /** Environment variable name mapped to the property it feeds. */
    static final Map<String, String> REQUIRED_IN_PROD = requiredInProd();

    private static Map<String, String> requiredInProd() {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("EDUCORE_DB_URL", "spring.datasource.url");
        required.put("EDUCORE_DB_USERNAME", "spring.datasource.username");
        required.put("EDUCORE_DB_PASSWORD", "spring.datasource.password");
        required.put("EDUCORE_JWT_SECRET", "educore.security.jwt.secret");
        required.put("EDUCORE_LOGIN_PEPPER", "educore.security.login.username-pepper");
        required.put("EDUCORE_BOOTSTRAP_ADMIN_USERNAME", "educore.bootstrap.admin.username");
        required.put("EDUCORE_BOOTSTRAP_ADMIN_PASSWORD", "educore.bootstrap.admin.password");
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
