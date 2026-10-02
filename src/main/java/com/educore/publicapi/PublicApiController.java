package com.educore.publicapi;

import com.educore.common.web.PageResponse;
import com.educore.common.web.Paging;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous, read-only endpoints for the public site ({@code /api/v1/public/**}, permitted by the URL rules
 * in {@code SecurityConfig}). Only published courses and organisation facts are exposed, as the record DTOs
 * {@link PublicCourseResponse} and {@link SiteFactsResponse}. Every successful response carries
 * {@code Cache-Control: public, max-age=300} and an {@code ETag} ({@link PublicCaching}); like every
 * {@code /api/**} response it also carries {@code X-Robots-Tag: noindex, nofollow} ({@link RobotsTagFilter}).
 */
@RestController
@RequestMapping("/api/v1/public")
@Validated
public class PublicApiController {

    private final PublicCatalogService catalog;
    private final PublicCaching caching;

    public PublicApiController(PublicCatalogService catalog, PublicCaching caching) {
        this.catalog = catalog;
        this.caching = caching;
    }

    /**
     * Published courses; {@code page >= 0}, {@code 1 <= size <= 100}, {@code sort} one of {@code name}
     * (default), {@code term}, {@code updatedAt}; {@code direction} {@code asc} (default) or {@code desc}.
     */
    @GetMapping("/courses")
    public ResponseEntity<PageResponse<PublicCourseResponse>> courses(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(Paging.MAX_SIZE) int size,
            @RequestParam(required = false) @Size(max = 20) String sort,
            @RequestParam(required = false) @Size(max = 4) String direction) {
        return caching.json(catalog.list(page, size, sort, direction));
    }

    /** One published course; 404 {@code course/not-found} for an unknown or unpublished slug. */
    @GetMapping("/courses/{slug}")
    public ResponseEntity<PublicCourseResponse> course(@PathVariable String slug) {
        return caching.json(catalog.bySlug(slug));
    }

    @GetMapping("/site-facts")
    public ResponseEntity<SiteFactsResponse> siteFacts() {
        return caching.json(catalog.siteFacts());
    }
}
