package com.educore.publicapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Organisation facts published by {@code GET /api/v1/public/site-facts}, bound from {@code educore.seo.*}
 * next to {@code educore.seo.base-url} and {@code educore.seo.ai-crawlers} (which stay in
 * {@link com.educore.config.EduCoreProperties.Seo}). All values are public by definition.
 *
 * @param name        organisation and site name ({@code educore.seo.name})
 * @param description one-sentence summary of the site ({@code educore.seo.description})
 * @param languages   content languages as BCP 47 tags, first one is the default ({@code educore.seo.languages})
 * @param sitemapMaxUrls URLs per sitemap file ({@code educore.seo.sitemap-max-urls}, default 45,000, protocol
 *                       limit 50,000); above it {@code /sitemap.xml} becomes a sitemap index
 */
@Validated
@ConfigurationProperties("educore.seo")
public record PublicSiteProperties(
        @NotBlank @Size(max = 100) @DefaultValue("EduCore") String name,
        @NotBlank @Size(max = 300)
        @DefaultValue("EduCore is a course and student management platform: a public course catalog, "
                + "self-service enrollment for students and administration tools for staff.")
        String description,
        @NotEmpty @DefaultValue({"tr", "en"}) List<@Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") String> languages,
        @Min(10) @Max(50_000) @DefaultValue("45000") int sitemapMaxUrls) {
}
