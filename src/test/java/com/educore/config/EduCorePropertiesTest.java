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
            assertThat(p.ipaccess().denyCacheTtl()).isEqualTo(Duration.ofSeconds(60));
            assertThat(p.ipaccess().autoDeny().failures()).isEqualTo(20);
            assertThat(p.ipaccess().autoDeny().window()).isEqualTo(Duration.ofMinutes(10));
            assertThat(p.ipaccess().autoDeny().duration()).isEqualTo(Duration.ofHours(1));
            assertThat(p.ratelimit().anonymousPerMinute()).isEqualTo(60);
            assertThat(p.ratelimit().authenticatedPerMinute()).isEqualTo(300);
            assertThat(p.ratelimit().publicPerMinute()).isEqualTo(120);
            assertThat(p.security().https().required()).isFalse();
            assertThat(p.ingestion().baseDir()).isEqualTo(Path.of("csv_uploads"));
            assertThat(p.ingestion().maxBytes()).isEqualTo(DataSize.ofMegabytes(20));
            assertThat(p.ingestion().maxRows()).isEqualTo(50_000);
            assertThat(p.webhook().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.webhook().maxRetries()).isEqualTo(5);
            assertThat(p.webhook().readTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.ingestion().threads()).isEqualTo(4);
            assertThat(p.ingestion().stableAfter()).isEqualTo(Duration.ofSeconds(2));
            assertThat(p.crypto().encryptionKey()).isNull();
            assertThat(p.crypto().toString()).doesNotContain("null").contains("<unset>");
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

    /**
     * The documented variable {@code EDUCORE_IPACCESS_TRUSTED_PROXIES} (.env.example, docker-compose.yml) reaches
     * {@code educore.ipaccess.trusted-proxies} through the explicit placeholder in application.yml. The source is
     * a {@code SystemEnvironmentPropertySource} named like the real one, so the exact OS variable name is used.
     */
    @Test
    void documentedEnvironmentVariableBindsTheTrustedProxies() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.SystemEnvironmentPropertySource("test-systemEnvironment",
                                java.util.Map.of("EDUCORE_IPACCESS_TRUSTED_PROXIES", "172.30.42.10,10.20.0.0/16"))))
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EduCoreProperties.class).ipaccess().trustedProxies())
                            .containsExactly("172.30.42.10", "10.20.0.0/16");
                });
        runner.withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .run(context -> assertThat(context.getBean(EduCoreProperties.class).ipaccess().trustedProxies())
                        .isEmpty());
    }

    /**
     * {@code EDUCORE_SEO_BASE_URL} (docker-compose.prod.yml, .env.example) is spelled with an underscore inside
     * {@code base-url}; relaxed binding alone would expect {@code EDUCORE_SEO_BASEURL}. The explicit placeholder in
     * application.yml makes the documented spelling work. Exact OS variable name, real property source type.
     */
    @Test
    void documentedEnvironmentVariableBindsTheSeoBaseUrl() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.SystemEnvironmentPropertySource("test-systemEnvironment",
                                java.util.Map.of("EDUCORE_SEO_BASE_URL", "https://educore.example.org"))))
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EduCoreProperties.class).seo().baseUrl())
                            .isEqualTo(URI.create("https://educore.example.org"));
                });
        runner.withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .run(context -> assertThat(context.getBean(EduCoreProperties.class).seo().baseUrl())
                        .isEqualTo(URI.create("http://localhost:3000")));
    }

    @Test
    void anOverlyBroadTrustedProxyFailsStartup() {
        runner.withUserConfiguration(com.educore.security.TrustedProxies.class)
                .withPropertyValues("educore.ipaccess.trusted-proxies=0.0.0.0/0")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("broader than /8"));
        runner.withUserConfiguration(com.educore.security.TrustedProxies.class)
                .withPropertyValues("educore.ipaccess.trusted-proxies=172.30.42.10")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void trustedProxiesBindFromACommaSeparatedValueAndAnEmptyValueMeansNone() {
        runner.withPropertyValues("educore.ipaccess.trusted-proxies=10.0.0.5,172.18.0.0/16")
                .run(context -> assertThat(context.getBean(EduCoreProperties.class).ipaccess().trustedProxies())
                        .containsExactly("10.0.0.5", "172.18.0.0/16"));
        runner.withPropertyValues("educore.ipaccess.trusted-proxies=")
                .run(context -> assertThat(context.getBean(EduCoreProperties.class).ipaccess().trustedProxies())
                        .isEmpty());
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
                Duration.ofMinutes(15), new EduCoreProperties.AccountThrottle(5, Duration.ofSeconds(1),
                Duration.ofSeconds(30), Duration.ofMinutes(15), Duration.ofDays(30)));

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
            assertThat(security.login().accountThrottle().freeFailures()).isEqualTo(5);
            assertThat(security.login().accountThrottle().baseDelay()).isEqualTo(Duration.ofSeconds(1));
            assertThat(security.login().accountThrottle().maxDelay()).isEqualTo(Duration.ofSeconds(30));
            assertThat(security.login().accountThrottle().trustedNetworkAge()).isEqualTo(Duration.ofDays(30));
            assertThat(security.refreshToken().ttl()).isEqualTo(Duration.ofDays(14));
            assertThat(security.refreshToken().cookieSecure()).isTrue();
        });
    }
}
