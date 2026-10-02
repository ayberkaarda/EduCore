package com.educore.publicapi;

import java.time.Instant;
import java.util.List;

/**
 * Organisation facts for structured data ({@code Organization}/{@code WebSite} JSON-LD) and {@code llms.txt}.
 *
 * @param name         organisation and site name
 * @param baseUrl      absolute origin of the public site, without a trailing slash
 * @param description  one-sentence summary
 * @param languages    content languages (BCP 47), the first is the default
 * @param dateModified last change of any published course; {@code null} while nothing is published
 */
public record SiteFactsResponse(String name, String baseUrl, String description, List<String> languages,
                                Instant dateModified) {
}
