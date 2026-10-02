package com.educore.config;

import com.educore.EduCoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Profile {@code prod} refuses to start when required environment variables are missing. The check runs
 * before any bean is created, so no database is needed. The environment is built without the real
 * process environment, so variables set on the build machine cannot influence the result.
 */
class ProdStartupGuardIT {

    /** Values only need to be present; the guard stops startup before any of them is used. */
    private static Map<String, Object> completeProdEnvironment() {
        Map<String, Object> env = new HashMap<>();
        env.put("EDUCORE_DB_URL", "jdbc:postgresql://127.0.0.1:1/unreachable");
        env.put("EDUCORE_DB_APP_USERNAME", "guard_app");
        env.put("EDUCORE_DB_APP_PASSWORD", "guard-test-db-value");
        env.put("EDUCORE_DB_MIGRATION_USERNAME", "guard_owner");
        env.put("EDUCORE_DB_MIGRATION_PASSWORD", "guard-test-owner-value");
        env.put("EDUCORE_ERASURE_LEDGER_FILE", "/var/lib/educore/erasure-ledger.log");
        env.put("EDUCORE_JWT_SECRET", "guard-test-jwt-value");
        env.put("EDUCORE_LOGIN_PEPPER", "guard-test-pepper-value");
        env.put("EDUCORE_ENCRYPTION_KEY", "guard-test-encryption-value");
        env.put("EDUCORE_BOOTSTRAP_ADMIN_USERNAME", "guard-admin");
        env.put("EDUCORE_BOOTSTRAP_ADMIN_PASSWORD", "guard-test-admin-value");
        env.put("EDUCORE_SEO_BASE_URL", "https://guard.example.org");
        return env;
    }

    private static Throwable startProd(Map<String, Object> variables) {
        return start("prod", variables);
    }

    private static Throwable start(String profiles, Map<String, Object> variables) {
        Map<String, Object> source = new HashMap<>(variables);
        source.put("spring.profiles.active", profiles);
        StandardEnvironment environment = new StandardEnvironment() {
            @Override
            protected void customizePropertySources(MutablePropertySources propertySources) {
                propertySources.addLast(new MapPropertySource("prodStartupGuardIT", source));
            }
        };
        SpringApplicationBuilder builder = new SpringApplicationBuilder(EduCoreApplication.class)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false);
        return catchThrowable(builder::run);
    }

    @Test
    void refusesToStartWithoutAnyRequiredVariable() {
        Throwable failure = startProd(Map.of());

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start with profile 'prod'");
        assertThat(failure.getMessage()).contains(
                "EDUCORE_DB_URL", "EDUCORE_DB_APP_USERNAME", "EDUCORE_DB_APP_PASSWORD", "EDUCORE_DB_MIGRATION_USERNAME",
                "EDUCORE_DB_MIGRATION_PASSWORD", "EDUCORE_ERASURE_LEDGER_FILE", "EDUCORE_JWT_SECRET",
                "EDUCORE_LOGIN_PEPPER", "EDUCORE_ENCRYPTION_KEY", "EDUCORE_BOOTSTRAP_ADMIN_USERNAME",
                "EDUCORE_BOOTSTRAP_ADMIN_PASSWORD", "EDUCORE_SEO_BASE_URL");
    }

    /**
     * AC-15: prod needs two database roles. A single-user setup (the application connecting as the schema owner,
     * a superuser in the compose image) is refused, also when only the legacy EDUCORE_DB_USERNAME is set.
     */
    @Test
    void refusesTheMigrationOwnerAsTheRuntimeRole() {
        Map<String, Object> env = completeProdEnvironment();
        env.put("EDUCORE_DB_MIGRATION_USERNAME", "GUARD_APP");

        Throwable failure = startProd(env);

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EDUCORE_DB_APP_USERNAME (the runtime role) must differ from "
                        + "EDUCORE_DB_MIGRATION_USERNAME")
                .hasMessageNotContaining("guard_app");

        Map<String, Object> legacy = completeProdEnvironment();
        legacy.remove("EDUCORE_DB_APP_USERNAME");
        legacy.put("EDUCORE_DB_USERNAME", "guard_owner");
        assertThat(startProd(legacy)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ from EDUCORE_DB_MIGRATION_USERNAME");
    }

    @Test
    void namesTheMissingMigrationRoleAndErasureLedger() {
        Map<String, Object> env = completeProdEnvironment();
        env.remove("EDUCORE_DB_MIGRATION_USERNAME");
        env.remove("EDUCORE_DB_MIGRATION_PASSWORD");
        env.remove("EDUCORE_ERASURE_LEDGER_FILE");

        Throwable failure = startProd(env);

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not set: EDUCORE_DB_MIGRATION_USERNAME, EDUCORE_DB_MIGRATION_PASSWORD, "
                        + "EDUCORE_ERASURE_LEDGER_FILE.");
    }

    /** prod has no default for the public origin: the development default never reaches production. */
    @Test
    void namesTheMissingSeoBaseUrl() {
        Map<String, Object> env = completeProdEnvironment();
        env.remove("EDUCORE_SEO_BASE_URL");

        Throwable failure = startProd(env);

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not set: EDUCORE_SEO_BASE_URL.");
    }

    /** An http origin or a localhost origin would break refresh/logout (Origin check) and publish wrong sitemaps. */
    @Test
    void refusesAnHttpOrLocalhostSeoBaseUrl() {
        for (String origin : java.util.List.of("http://educore.example.org", "http://localhost:3000",
                "https://localhost", "https://127.0.0.1:8443", "https://[::1]")) {
            Map<String, Object> env = completeProdEnvironment();
            env.put("EDUCORE_SEO_BASE_URL", origin);

            Throwable failure = startProd(env);

            assertThat(failure).as(origin).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("EDUCORE_SEO_BASE_URL must be the public https origin")
                    .hasMessageNotContaining(origin);
        }
    }

    @Test
    void namesOnlyTheMissingEncryptionKey() {
        Map<String, Object> env = completeProdEnvironment();
        env.remove("EDUCORE_ENCRYPTION_KEY");

        Throwable failure = startProd(env);

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not set: EDUCORE_ENCRYPTION_KEY.")
                .hasMessageNotContaining("guard-test-encryption-value");
    }

    @Test
    void namesOnlyTheMissingBootstrapAdminPassword() {
        Map<String, Object> env = completeProdEnvironment();
        env.remove("EDUCORE_BOOTSTRAP_ADMIN_PASSWORD");

        Throwable failure = startProd(env);

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("not set: EDUCORE_BOOTSTRAP_ADMIN_PASSWORD. "
                        + "See .env.example for the meaning of each variable.");
    }

    @Test
    void blankJwtSecretCountsAsMissing() {
        Map<String, Object> env = completeProdEnvironment();
        env.put("EDUCORE_JWT_SECRET", "  ");

        Throwable failure = startProd(env);

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not set: EDUCORE_JWT_SECRET.");
    }

    @Test
    void refusesProdTogetherWithDev() {
        Throwable failure = start("prod,dev", completeProdEnvironment());

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile 'dev' must not be active at the same time");
    }

    @Test
    void refusesProdTogetherWithTest() {
        Throwable failure = start("test,prod", completeProdEnvironment());

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile 'test' must not be active at the same time");
    }

    @Test
    void refusesSeedLocationInProd() {
        Map<String, Object> env = completeProdEnvironment();
        env.put("spring.flyway.locations", "classpath:db/migration,classpath:db/seed/dev");

        Throwable failure = startProd(env);

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.locations includes a seed location");
    }

    @Test
    void failureMessageNeverContainsSuppliedValues() {
        Map<String, Object> env = completeProdEnvironment();
        env.remove("EDUCORE_DB_URL");

        assertThatThrownBy(() -> {
            Throwable failure = startProd(env);
            if (failure != null) {
                throw failure;
            }
        }).hasMessageContaining("EDUCORE_DB_URL")
                .hasMessageNotContaining("guard-test-db-value")
                .hasMessageNotContaining("guard-test-admin-value")
                .hasMessageNotContaining("guard-test-pepper-value");
    }
}
