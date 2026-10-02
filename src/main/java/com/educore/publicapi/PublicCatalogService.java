package com.educore.publicapi;

import com.educore.common.web.ApiProblemException;
import com.educore.common.web.PageResponse;
import com.educore.common.web.Paging;
import com.educore.common.web.SortWhitelist;
import com.educore.config.EduCoreProperties;
import com.educore.course.CatalogRevision;
import com.educore.course.CourseSlugs;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The anonymous, read-only view of the course catalog: published courses only, public fields only.
 */
@Service
public class PublicCatalogService {

    static final String COURSE_NOT_FOUND = "course/not-found";

    /** {@code sort} keys of the listing; ties are broken by the (unique) slug. */
    static final SortWhitelist SORT = SortWhitelist.of("name", Sort.by("slug"))
            .allow("name", "name")
            .allow("term", "term")
            .allow("updatedAt", "updatedAt");

    private static final Pattern SLUG = Pattern.compile(CourseSlugs.PATTERN);

    private final PublicCourseRepository repository;
    private final EduCoreProperties properties;
    private final PublicSiteProperties site;
    private final CatalogRevision catalogRevision;

    public PublicCatalogService(PublicCourseRepository repository, EduCoreProperties properties,
                                PublicSiteProperties site, CatalogRevision catalogRevision) {
        this.repository = repository;
        this.properties = properties;
        this.site = site;
        this.catalogRevision = catalogRevision;
    }

    /** One page of published courses ordered by a whitelisted key ({@link #SORT}). */
    @Transactional(readOnly = true)
    public PageResponse<PublicCourseResponse> list(int page, int size, String sort, String direction) {
        return PageResponse.of(repository.findPublished(Paging.of(page, size, SORT.resolve(sort, direction))));
    }

    /**
     * The published course with this slug.
     *
     * @throws ApiProblemException 404 {@code course/not-found} for an unknown, unpublished or malformed slug
     */
    @Transactional(readOnly = true)
    public PublicCourseResponse bySlug(String slug) {
        if (slug == null || slug.length() > CourseSlugs.MAX_LENGTH || !SLUG.matcher(slug).matches()) {
            throw notFound();
        }
        return repository.findPublishedBySlug(slug).orElseThrow(PublicCatalogService::notFound);
    }

    /**
     * Course URLs per sitemap file: {@code educore.seo.sitemap-max-urls} minus the static routes, which the
     * first file also lists.
     */
    public int coursesPerSitemap() {
        return site.sitemapMaxUrls() - SitemapController.STATIC_ROUTES.size();
    }

    /** Number of sitemap files needed for every published course (at least one). */
    @Transactional(readOnly = true)
    public int sitemapFiles() {
        long published = repository.countPublished();
        return (int) Math.max(1, (published + coursesPerSitemap() - 1) / coursesPerSitemap());
    }

    /** The published courses of sitemap file {@code file} (1-based), by name. */
    @Transactional(readOnly = true)
    public List<PublicCourseResponse> sitemapCourses(int file) {
        return repository.findPublishedSlice(PageRequest.of(file - 1, coursesPerSitemap(), Sort.by("name", "slug")));
    }

    /**
     * The catalog revision ({@link CatalogRevision}): moves forward on every change visible to visitors,
     * unpublish and delete included; empty while nothing is published.
     */
    public Optional<Instant> catalogModified() {
        return catalogRevision.current();
    }

    public SiteFactsResponse siteFacts() {
        return new SiteFactsResponse(site.name(), baseUrl(), site.description(), List.copyOf(site.languages()),
                catalogModified().orElse(null));
    }

    /** {@code educore.seo.base-url} without a trailing slash. */
    public String baseUrl() {
        String configured = properties.seo().baseUrl().toString();
        return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
    }

    private static ApiProblemException notFound() {
        return ApiProblemException.notFound(COURSE_NOT_FOUND, "Course not found.");
    }
}
