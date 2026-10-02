package com.educore.publicapi;

import com.educore.config.EduCoreProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code educore.seo.base-url} prefixes every sitemap and site-facts URL, so the application refuses to start
 * unless it is an absolute http(s) origin without user info, path, query or fragment; likewise
 * {@code educore.seo.sitemap-max-urls} must stay within the sitemap protocol limits.
 */
class SeoBaseUrlValidationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({EduCoreProperties.class, PublicSiteProperties.class})
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @ParameterizedTest
    @ValueSource(strings = {"https://educore.example", "https://educore.example/", "http://localhost:3000",
            "https://www.educore.example:8443"})
    void originsAreAccepted(String baseUrl) {
        runner.withPropertyValues("educore.seo.base-url=" + baseUrl).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EduCoreProperties.class).seo().baseUrl().toString()).isEqualTo(baseUrl);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"educore.example", "/relative", "ftp://educore.example", "javascript:alert(1)",
            "https://user:secret@educore.example", "https://educore.example/site", "https://educore.example?x=1",
            "https://educore.example#top", "https://educore.example/#", "mailto:team@educore.example", "https:///path"})
    void everythingElseFailsStartup(String baseUrl) {
        runner.withPropertyValues("educore.seo.base-url=" + baseUrl).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("educore.seo.base-url");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"9", "50001", "-1"})
    void sitemapLimitOutsideTheProtocolFailsStartup(String limit) {
        runner.withPropertyValues("educore.seo.sitemap-max-urls=" + limit)
                .run(context -> assertThat(context).hasFailed());
    }
}
