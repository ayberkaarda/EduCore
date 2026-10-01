package com.educore.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Binding, defaults and fail-fast validation of the {@code educore.*} record tree. */
class EduCorePropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EduCoreProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void defaultsApplyWhenNothingIsConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            EduCoreProperties p = context.getBean(EduCoreProperties.class);
            assertThat(p.security().jwt().secret()).isNull();
            assertThat(p.cors().allowedOrigins()).isEmpty();
            assertThat(p.ipaccess().trustedProxies()).isEmpty();
            assertThat(p.ingestion().baseDir()).isEqualTo(Path.of("csv_uploads"));
            assertThat(p.ingestion().maxBytes()).isEqualTo(DataSize.ofMegabytes(20));
            assertThat(p.ingestion().maxRows()).isEqualTo(50_000);
            assertThat(p.webhook().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.webhook().maxAttempts()).isEqualTo(5);
            assertThat(p.seo().baseUrl()).isEqualTo(URI.create("http://localhost:3000"));
            assertThat(p.seo().aiCrawlers().allowPublic()).isTrue();
            assertThat(p.seo().aiCrawlers().userAgents()).contains("GPTBot", "ClaudeBot", "CCBot");
            assertThat(p.problems().baseUrl()).isEqualTo(URI.create("/problems"));
            assertThat(p.bootstrap().admin().isConfigured()).isFalse();
        });
    }

    @Test
    void commaSeparatedCorsOriginsBindToAList() {
        runner.withPropertyValues("educore.cors.allowed-origins=http://localhost:3000,https://app.example.org")
                .run(context -> assertThat(context.getBean(EduCoreProperties.class).cors().allowedOrigins())
                        .containsExactly("http://localhost:3000", "https://app.example.org"));
    }

    @Test
    void emptyBootstrapValuesMeanNotConfigured() {
        runner.withPropertyValues("educore.bootstrap.admin.username=", "educore.bootstrap.admin.password=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EduCoreProperties.class).bootstrap().admin().isConfigured()).isFalse();
                });
    }

    @Test
    void bootstrapUsernameWithoutPasswordFailsFast() {
        runner.withPropertyValues("educore.bootstrap.admin.username=first-admin")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("must be set together"));
    }

    @Test
    void shortBootstrapPasswordFailsFast() {
        runner.withPropertyValues("educore.bootstrap.admin.username=first-admin",
                        "educore.bootstrap.admin.password=short")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("EDUCORE_BOOTSTRAP_ADMIN_PASSWORD must be at least 12 characters"));
    }

    @Test
    void invalidIngestionLimitFailsFast() {
        runner.withPropertyValues("educore.ingestion.max-rows=0")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("educore.ingestion.maxRows"));
    }

    @Test
    void secretsAreRedactedInToString() {
        EduCoreProperties.Jwt jwt = new EduCoreProperties.Jwt("c2VjcmV0LXZhbHVlLWZvci10b3N0cmluZy10ZXN0",
                "cHJldmlvdXMtdmFsdWUtZm9yLXRvc3RyaW5nLXRlc3Q", "educore", "educore-api", Duration.ofMinutes(15));
        EduCoreProperties.Admin admin = new EduCoreProperties.Admin("first-admin", "to-string-test-value");
        EduCoreProperties.Login login = new EduCoreProperties.Login("pepper-value-for-tostring-test", 10, 5,
                Duration.ofMinutes(15));

        assertThat(jwt.toString()).doesNotContain("c2VjcmV0").doesNotContain("cHJldmlvdXM").contains("<redacted>");
        assertThat(admin.toString()).doesNotContain("to-string-test-value").contains("first-admin");
        assertThat(login.toString()).doesNotContain("pepper-value-for-tostring-test").contains("<redacted>");
    }

    @Test
    void authDefaultsApplyWhenNothingIsConfigured() {
        runner.run(context -> {
            EduCoreProperties.Security security = context.getBean(EduCoreProperties.class).security();
            assertThat(security.jwt().previousSecret()).isNull();
            assertThat(security.jwt().issuer()).isEqualTo("educore");
            assertThat(security.jwt().audience()).isEqualTo("educore-api");
            assertThat(security.jwt().accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
            assertThat(security.login().usernamePepper()).isNull();
            assertThat(security.login().ipAttemptsPerMinute()).isEqualTo(10);
            assertThat(security.login().maxFailures()).isEqualTo(5);
            assertThat(security.login().lockDuration()).isEqualTo(Duration.ofMinutes(15));
            assertThat(security.refreshToken().ttl()).isEqualTo(Duration.ofDays(14));
            assertThat(security.refreshToken().cookieSecure()).isTrue();
        });
    }
}
