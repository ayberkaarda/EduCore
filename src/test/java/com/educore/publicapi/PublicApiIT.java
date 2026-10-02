package com.educore.publicapi;

import com.educore.course.CatalogRevision;
import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.StringReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The anonymous public API ({@code /api/v1/public/**}) and {@code /sitemap.xml}: anonymous access, only
 * published courses, 404 problems for unpublished or unknown slugs, {@code ETag}/{@code 304} behaviour,
 * caching and robots headers, sitemap content validated against the sitemaps.org structure, site facts, the
 * catalog revision, HEAD and CORS, conditional-request negatives, and the public catalog fields accepted by
 * the ADMIN course routes.
 */
class PublicApiIT extends PublicApiTestSupport {

    private static final List<String> PUBLIC_COURSE_FIELDS =
            List.of("name", "slug", "term", "instructor", "description", "updatedAt");

    /**
     * The sitemaps.org 0.9 structure used by this site ({@code urlset} of {@code url} with a required
     * {@code loc} and an optional W3C-datetime {@code lastmod}).
     */
    private static final String SITEMAP_XSD = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                       targetNamespace="http://www.sitemaps.org/schemas/sitemap/0.9"
                       xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" elementFormDefault="qualified">
              <xs:element name="urlset">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="url" maxOccurs="50000">
                      <xs:complexType>
                        <xs:sequence>
                          <xs:element name="loc">
                            <xs:simpleType>
                              <xs:restriction base="xs:anyURI">
                                <xs:minLength value="12"/>
                                <xs:maxLength value="2048"/>
                              </xs:restriction>
                            </xs:simpleType>
                          </xs:element>
                          <xs:element name="lastmod" minOccurs="0">
                            <xs:simpleType>
                              <xs:restriction base="xs:string">
                                <xs:pattern value="\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+\\-]\\d{2}:\\d{2})"/>
                              </xs:restriction>
                            </xs:simpleType>
                          </xs:element>
                        </xs:sequence>
                      </xs:complexType>
                    </xs:element>
                  </xs:sequence>
                </xs:complexType>
              </xs:element>
            </xs:schema>
            """;

    private static final String BASE_URL = "http://localhost:3000";

    @Autowired
    private CatalogRevision catalogRevisionBean;

    // ---- anonymous access and published-only ----------------------------------------------------------

    @Test
    void anonymousListContainsOnlyPublishedCoursesWithPublicFields() throws Exception {
        Course first = publishedCourse(uniqueSlug("alpha"), "First public course.");
        Course second = publishedCourse(uniqueSlug("beta"), "Second public course.");
        Course hidden = unpublishedCourse(uniqueSlug("hidden"), "Not published.");

        MvcResult result = perform(null, get("/api/v1/public/courses").param("size", "100"), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertCachingHeaders(result);
        assertThat(result.getResponse().getHeader(RobotsTagFilter.HEADER)).isEqualTo("noindex, nofollow");
        JsonNode page = body(result);
        List<String> slugs = new ArrayList<>();
        page.get("content").forEach(item -> slugs.add(item.get("slug").asText()));
        assertThat(slugs).contains(first.getSlug(), second.getSlug()).doesNotContain(hidden.getSlug());
        assertThat(slugs).hasSize(page.get("totalElements").asInt());
        page.get("content").forEach(item -> assertThat(fieldNames(item)).containsExactlyElementsOf(PUBLIC_COURSE_FIELDS));

        JsonNode item = itemWithSlug(page, first.getSlug());
        assertThat(item.get("name").asText()).isEqualTo(first.getName());
        assertThat(item.get("term").asText()).isEqualTo("2026/1");
        assertThat(item.get("instructor").asText()).isEqualTo(first.getInstructor());
        assertThat(item.get("description").asText()).isEqualTo("First public course.");
        assertThat(Instant.parse(item.get("updatedAt").asText())).isEqualTo(storedUpdatedAt(first));
    }

    @Test
    void authenticatedCallersSeeTheSamePublicCatalog() throws Exception {
        Course course = publishedCourse(uniqueSlug("shared"), "Visible to everyone.");
        Account user = account(Role.USER);

        MvcResult anonymous = perform(null, get("/api/v1/public/courses/" + course.getSlug()), null);
        MvcResult authenticated = perform(user, get("/api/v1/public/courses/" + course.getSlug()), null);

        assertThat(anonymous.getResponse().getStatus()).isEqualTo(200);
        assertThat(authenticated.getResponse().getStatus()).isEqualTo(200);
        assertThat(authenticated.getResponse().getContentAsString()).isEqualTo(anonymous.getResponse().getContentAsString());
    }

    @Test
    void listingIsPagedAndSortedThroughAWhitelist() throws Exception {
        Course older = publishedCourse(uniqueSlug("paged"), "Older.");
        Course newer = publishedCourse(uniqueSlug("paged"), "Newer.");
        jdbc.update("UPDATE course SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), older.getId());
        jdbc.update("UPDATE course SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-06-01T00:00:00Z")), newer.getId());

        MvcResult newestFirst = perform(null, get("/api/v1/public/courses")
                .param("sort", "updatedAt").param("direction", "desc").param("size", "100"), null);
        List<String> slugs = new ArrayList<>();
        body(newestFirst).get("content").forEach(item -> slugs.add(item.get("slug").asText()));
        assertThat(slugs.indexOf(newer.getSlug())).isLessThan(slugs.indexOf(older.getSlug()));

        MvcResult onePerPage = perform(null, get("/api/v1/public/courses").param("size", "1"), null);
        assertThat(body(onePerPage).get("content")).hasSize(1);
        assertThat(body(onePerPage).get("size").asInt()).isEqualTo(1);

        assertProblem(perform(null, get("/api/v1/public/courses").param("size", "101"), null), 400, "request/invalid");
        assertProblem(perform(null, get("/api/v1/public/courses").param("size", "0"), null), 400, "request/invalid");
        assertProblem(perform(null, get("/api/v1/public/courses").param("page", "-1"), null), 400, "request/invalid");
        for (String key : List.of("id", "published", "password", "enrollments", "name;drop")) {
            assertProblem(perform(null, get("/api/v1/public/courses").param("sort", key), null), 400, "sort/invalid");
        }
        assertProblem(perform(null, get("/api/v1/public/courses").param("direction", "up"), null), 400, "sort/invalid");
    }

    @Test
    void detailAnswersPublishedCoursesAnd404ProblemsOtherwise() throws Exception {
        Course course = publishedCourse(uniqueSlug("detail"), "Detail page.");
        Course hidden = unpublishedCourse(uniqueSlug("draft"), "Draft.");

        MvcResult found = perform(null, get("/api/v1/public/courses/" + course.getSlug()), null);
        assertThat(found.getResponse().getStatus()).isEqualTo(200);
        assertCachingHeaders(found);
        assertThat(fieldNames(body(found))).containsExactlyElementsOf(PUBLIC_COURSE_FIELDS);
        assertThat(body(found).get("slug").asText()).isEqualTo(course.getSlug());

        for (String slug : List.of(hidden.getSlug(), uniqueSlug("unknown"), "Upper-Case", "a--b", "x".repeat(81))) {
            MvcResult missing = perform(null, get("/api/v1/public/courses/" + slug), null);
            assertProblem(missing, 404, "course/not-found");
            assertThat(missing.getResponse().getHeader(HttpHeaders.ETAG)).isNull();
            assertThat(missing.getResponse().getHeader(RobotsTagFilter.HEADER)).isEqualTo("noindex, nofollow");
            assertThat(missing.getResponse().getContentAsString()).doesNotContain(hidden.getName());
        }
    }

    // ---- ETag and 304 -----------------------------------------------------------------------------------

    @Test
    void matchingIfNoneMatchAnswers304UntilTheCourseChanges() throws Exception {
        Course course = publishedCourse(uniqueSlug("etag"), "Before.");
        String detail = "/api/v1/public/courses/" + course.getSlug();

        for (String path : List.of(detail, "/api/v1/public/courses", "/api/v1/public/site-facts", "/sitemap.xml")) {
            MvcResult first = perform(null, get(path), null);
            String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
            assertThat(etag).as(path).matches("\"[0-9a-f]{32}\"");

            MvcResult again = perform(null, get(path), null);
            assertThat(again.getResponse().getHeader(HttpHeaders.ETAG)).as("stable ETag of %s", path).isEqualTo(etag);

            MvcResult notModified = perform(null, get(path).header(HttpHeaders.IF_NONE_MATCH, etag), null);
            assertThat(notModified.getResponse().getStatus()).as(path).isEqualTo(304);
            assertThat(notModified.getResponse().getContentAsString()).as(path).isEmpty();
            assertThat(notModified.getResponse().getHeader(HttpHeaders.ETAG)).as(path).isEqualTo(etag);
            assertThat(notModified.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).as(path)
                    .isEqualTo("max-age=300, public");

            MvcResult otherTag = perform(null, get(path).header(HttpHeaders.IF_NONE_MATCH, "\"0000\""), null);
            assertThat(otherTag.getResponse().getStatus()).as(path).isEqualTo(200);
        }

        String before = perform(null, get(detail), null).getResponse().getHeader(HttpHeaders.ETAG);
        course.setDescription("After.");
        courseRepository.saveAndFlush(course);

        MvcResult changed = perform(null, get(detail).header(HttpHeaders.IF_NONE_MATCH, before), null);
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(changed.getResponse().getHeader(HttpHeaders.ETAG)).isNotEqualTo(before);
        assertThat(body(changed).get("description").asText()).isEqualTo("After.");
    }

    // ---- site facts ---------------------------------------------------------------------------------------

    @Test
    void siteFactsCarryOrganisationFactsAndTheLatestPublishedChange() throws Exception {
        Course course = publishedCourse(uniqueSlug("facts"), "Facts.");
        Course draft = unpublishedCourse(uniqueSlug("facts-draft"), "Draft.");
        jdbc.update("UPDATE course SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2031-01-01T00:00:00Z")), draft.getId());
        Instant revision = catalogRevision();

        MvcResult result = perform(null, get("/api/v1/public/site-facts"), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertCachingHeaders(result);
        JsonNode facts = body(result);
        assertThat(fieldNames(facts)).containsExactly("name", "baseUrl", "description", "languages", "dateModified");
        assertThat(facts.get("name").asText()).isEqualTo("EduCore");
        assertThat(facts.get("baseUrl").asText()).isEqualTo(BASE_URL);
        assertThat(facts.get("description").asText()).startsWith("EduCore is a course and student management platform");
        assertThat(facts.get("languages")).extracting(JsonNode::asText).containsExactly("tr", "en");
        assertThat(Instant.parse(facts.get("dateModified").asText())).isEqualTo(revision)
                .isAfterOrEqualTo(storedUpdatedAt(course))
                .isBefore(Instant.parse("2031-01-01T00:00:00Z"));
    }

    // ---- sitemap ------------------------------------------------------------------------------------------

    @Test
    void sitemapListsStaticRoutesAndPublishedCoursesAsValidXml() throws Exception {
        Course course = publishedCourse(uniqueSlug("sitemap"), "In the sitemap.");
        Course hidden = unpublishedCourse(uniqueSlug("sitemap-draft"), "Not in the sitemap.");

        MvcResult result = perform(null, get("/sitemap.xml"), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType())
                .isCompatibleWith(MediaType.APPLICATION_XML)).isTrue();
        assertCachingHeaders(result);
        assertThat(result.getResponse().getHeader(RobotsTagFilter.HEADER)).isNull();
        String xml = result.getResponse().getContentAsString();
        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");

        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(new StreamSource(new StringReader(SITEMAP_XSD)))
                .newValidator().validate(new StreamSource(new StringReader(xml)));

        Map<String, String> lastmodByLoc = parseSitemap(xml);
        assertThat(lastmodByLoc).containsKeys(BASE_URL + "/", BASE_URL + "/courses", BASE_URL + "/about",
                BASE_URL + "/faq", BASE_URL + "/privacy", BASE_URL + "/security",
                BASE_URL + "/courses/" + course.getSlug());
        assertThat(lastmodByLoc).doesNotContainKey(BASE_URL + "/courses/" + hidden.getSlug());
        assertThat(lastmodByLoc).allSatisfy((loc, lastmod) -> assertThat(loc).startsWith(BASE_URL + "/"));
        assertThat(lastmodByLoc.get(BASE_URL + "/courses/" + course.getSlug()))
                .isEqualTo(w3c(storedUpdatedAt(course)));
        assertThat(lastmodByLoc.get(BASE_URL + "/")).isEqualTo(w3c(catalogRevision()));
        assertThat(lastmodByLoc.get(BASE_URL + "/courses")).isEqualTo(w3c(catalogRevision()));
        assertThat(lastmodByLoc.get(BASE_URL + "/about")).isNull();
        assertThat(xml).doesNotContain(hidden.getName()).doesNotContain(hidden.getSlug());

        Integer published = jdbc.queryForObject("SELECT count(*) FROM course WHERE published", Integer.class);
        assertThat(lastmodByLoc).hasSize(SitemapController.STATIC_ROUTES.size() + published);
    }

    // ---- robots header ------------------------------------------------------------------------------------

    @Test
    void everyApiResponseCarriesTheNoindexHeaderButTheSitemapDoesNot() throws Exception {
        Account user = account(Role.USER);
        List<MvcResult> apiResponses = List.of(
                perform(null, get("/api/v1/courses"), null),
                perform(user, get("/api/v1/courses"), null),
                perform(user, get("/api/v1/admin/accounts"), null),
                perform(null, get("/api/v1/public/site-facts"), null),
                perform(null, get("/api/v1/public/unknown"), null),
                perform(null, post("/api/v1/public/courses"), null),
                perform(null, post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"), null));
        assertThat(apiResponses).extracting(r -> r.getResponse().getStatus()).containsExactly(401, 200, 403, 200, 404,
                401, 400);
        assertThat(apiResponses).allSatisfy(r ->
                assertThat(r.getResponse().getHeader(RobotsTagFilter.HEADER)).isEqualTo("noindex, nofollow"));

        assertThat(perform(null, get("/sitemap.xml"), null).getResponse().getHeader(RobotsTagFilter.HEADER)).isNull();
    }

    // ---- ADMIN course routes accept the public fields ---------------------------------------------------

    @Test
    void adminCourseRoutesManageThePublicFields() throws Exception {
        Account admin = account(Role.ADMIN);
        String name = "Çağdaş Türk Şiiri " + uniqueSlug("x");
        cleanUpCourseName(name);

        MvcResult created = perform(admin, post("/api/v1/admin/courses"), map("name", name, "term", "2026/2",
                "instructor", "Instructor Public", "description", "Modern poetry.", "published", true));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode course = body(created);
        String generated = course.get("slug").asText();
        assertThat(generated).startsWith("cagdas-turk-siiri-x-").matches(com.educore.course.CourseSlugs.PATTERN);
        assertThat(course.get("published").asBoolean()).isTrue();
        assertThat(perform(null, get("/api/v1/public/courses/" + generated), null).getResponse().getStatus())
                .isEqualTo(200);
        long id = course.get("id").asLong();

        // A client that does not send the public fields (the existing admin screen) keeps them unchanged.
        MvcResult renamed = perform(admin, put("/api/v1/admin/courses/" + id),
                map("name", name, "term", "2026/3", "instructor", "Instructor Public"));
        assertThat(renamed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(renamed).get("slug").asText()).isEqualTo(generated);
        assertThat(body(renamed).get("description").asText()).isEqualTo("Modern poetry.");
        assertThat(body(renamed).get("published").asBoolean()).isTrue();

        // The slug of a published course is immutable (an unpublish in the same request does not help) ...
        assertProblem(perform(admin, put("/api/v1/admin/courses/" + id),
                map("name", name, "slug", "modern-poetry-" + id)), 409, "course/slug-immutable");
        assertProblem(perform(admin, put("/api/v1/admin/courses/" + id),
                map("name", name, "slug", "modern-poetry-" + id, "published", false)), 409, "course/slug-immutable");
        // ... sending the unchanged slug is fine ...
        assertThat(perform(admin, put("/api/v1/admin/courses/" + id), map("name", name, "slug", generated))
                .getResponse().getStatus()).isEqualTo(200);
        // ... and an unpublished course may change it.
        MvcResult unpublished = perform(admin, put("/api/v1/admin/courses/" + id),
                map("name", name, "published", false, "description", ""));
        assertThat(unpublished.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(unpublished).get("description").isNull()).isTrue();
        MvcResult reslugged = perform(admin, put("/api/v1/admin/courses/" + id),
                map("name", name, "slug", "modern-poetry-" + id));
        assertThat(reslugged.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(reslugged).get("slug").asText()).isEqualTo("modern-poetry-" + id);
        assertThat(body(reslugged).get("published").asBoolean()).isFalse();
        assertProblem(perform(null, get("/api/v1/public/courses/modern-poetry-" + id), null), 404, "course/not-found");

        Course other = publishedCourse(uniqueSlug("taken"), "Owner of the slug.");
        assertProblem(perform(admin, put("/api/v1/admin/courses/" + id),
                map("name", name, "slug", other.getSlug())), 409, "course/slug-taken");
        for (Object invalid : List.of("Upper", "two--hyphens", "-leading", "trailing-", "space here", "x".repeat(81))) {
            assertProblem(perform(admin, put("/api/v1/admin/courses/" + id), map("name", name, "slug", invalid)),
                    400, "request/invalid");
        }
        assertProblem(perform(admin, put("/api/v1/admin/courses/" + id),
                map("name", name, "description", "d".repeat(1001))), 400, "request/invalid");
    }

    @Test
    void updatingACourseMovesItsUpdatedAtForward() throws Exception {
        Account admin = account(Role.ADMIN);
        Course course = publishedCourse(uniqueSlug("touch"), "Touch.");
        Instant past = Instant.parse("2025-01-01T00:00:00Z");
        jdbc.update("UPDATE course SET updated_at = ? WHERE id = ?", Timestamp.from(past), course.getId());

        MvcResult updated = perform(admin, put("/api/v1/admin/courses/" + course.getId()),
                map("name", course.getName(), "term", "2027/1", "instructor", course.getInstructor()));

        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(storedUpdatedAt(course)).isAfter(past.plus(1, ChronoUnit.DAYS));
    }

    // ---- catalog revision -----------------------------------------------------------------------------

    @Test
    void unpublishAndDeleteMoveTheCatalogRevisionForward() throws Exception {
        Account admin = account(Role.ADMIN);
        Course remaining = publishedCourse(uniqueSlug("rev-keep"), "Stays published.");
        Course withdrawn = publishedCourse(uniqueSlug("rev-unpub"), "Gets unpublished.");
        Course deleted = publishedCourse(uniqueSlug("rev-del"), "Gets deleted.");
        Instant old = Instant.parse("2025-01-01T00:00:00Z");
        for (Course course : List.of(remaining, withdrawn, deleted)) {
            jdbc.update("UPDATE course SET updated_at = ? WHERE id = ?", Timestamp.from(old), course.getId());
        }

        Instant beforeUnpublish = databaseNow();
        assertThat(perform(admin, put("/api/v1/admin/courses/" + withdrawn.getId()),
                map("name", withdrawn.getName(), "published", false)).getResponse().getStatus()).isEqualTo(200);
        Instant afterUnpublish = siteFactsDateModified();
        assertThat(afterUnpublish).isAfterOrEqualTo(beforeUnpublish);

        Instant beforeDelete = databaseNow();
        assertThat(perform(admin, delete("/api/v1/admin/courses/" + deleted.getId()), null)
                .getResponse().getStatus()).isEqualTo(204);
        Instant afterDelete = siteFactsDateModified();
        assertThat(afterDelete).isAfterOrEqualTo(beforeDelete).isAfterOrEqualTo(afterUnpublish);

        // A change that no visitor can see (an unpublished course edited) leaves the revision alone.
        assertThat(perform(admin, put("/api/v1/admin/courses/" + withdrawn.getId()),
                map("name", withdrawn.getName(), "term", "2030/1")).getResponse().getStatus()).isEqualTo(200);
        assertThat(siteFactsDateModified()).isEqualTo(afterDelete);

        // Never backwards: the revision stays ahead of the older courses that remain published.
        assertThat(afterDelete).isAfter(old);
        String sitemap = perform(null, get("/sitemap.xml"), null).getResponse().getContentAsString();
        assertThat(parseSitemap(sitemap).get(BASE_URL + "/courses")).isEqualTo(w3c(afterDelete));
    }

    // ---- HEAD, CORS and conditional request negatives --------------------------------------------------

    @Test
    void anonymousHeadRequestsAnswerLikeGetWithoutABody() throws Exception {
        Course course = publishedCourse(uniqueSlug("head"), "Head.");
        for (String path : List.of("/api/v1/public/courses", "/api/v1/public/courses/" + course.getSlug(),
                "/api/v1/public/site-facts", "/sitemap.xml", "/sitemap-courses-1.xml")) {
            MvcResult getResult = perform(null, get(path), null);
            MvcResult headResult = perform(null, head(path), null);
            // The servlet container discards a HEAD body; MockMvc keeps it, so only status and headers count here.
            assertThat(headResult.getResponse().getStatus()).as(path).isEqualTo(200);
            assertCachingHeaders(headResult);
            assertThat(headResult.getResponse().getHeader(HttpHeaders.ETAG)).as(path)
                    .isEqualTo(getResult.getResponse().getHeader(HttpHeaders.ETAG));

            MvcResult notModified = perform(null, head(path)
                    .header(HttpHeaders.IF_NONE_MATCH, getResult.getResponse().getHeader(HttpHeaders.ETAG)), null);
            assertThat(notModified.getResponse().getStatus()).as(path).isEqualTo(304);
        }
        assertThat(perform(null, head("/api/v1/public/courses/" + uniqueSlug("none")), null)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void corsPreflightAllowsHeadOnThePublicRoutes() throws Exception {
        for (String path : List.of("/api/v1/public/courses", "/api/v1/public/site-facts", "/sitemap.xml")) {
            MvcResult preflight = mockMvc.perform(options(path)
                    .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "HEAD")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "if-none-match")).andReturn();
            assertThat(preflight.getResponse().getStatus()).as(path).isEqualTo(200);
            assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
            assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains("HEAD");
        }
        MvcResult head = perform(null, head("/api/v1/public/site-facts").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN), null);
        assertThat(head.getResponse().getStatus()).isEqualTo(200);
        assertThat(head.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
    }

    @Test
    void conditionalRequestsFollowWeakComparisonListsAndWildcard() throws Exception {
        Course course = publishedCourse(uniqueSlug("cond"), "Conditional.");
        String path = "/api/v1/public/courses/" + course.getSlug();
        String etag = perform(null, get(path), null).getResponse().getHeader(HttpHeaders.ETAG);

        // If-None-Match uses the weak comparison (RFC 9110 13.1.2): W/ prefixed, listed and * validators match.
        for (String header : List.of("W/" + etag, "\"0000\", " + etag, "W/\"0000\", W/" + etag, "*")) {
            MvcResult result = perform(null, get(path).header(HttpHeaders.IF_NONE_MATCH, header), null);
            assertThat(result.getResponse().getStatus()).as(header).isEqualTo(304);
        }
        for (String header : List.of("\"0000\"", "W/\"0000\", \"9000000\"")) {
            MvcResult result = perform(null, get(path).header(HttpHeaders.IF_NONE_MATCH, header), null);
            assertThat(result.getResponse().getStatus()).as(header).isEqualTo(200);
        }

        // An old validator never revives an unpublished course: 404, not 304.
        course.setPublished(false);
        courseRepository.saveAndFlush(course);
        MvcResult gone = perform(null, get(path).header(HttpHeaders.IF_NONE_MATCH, etag), null);
        assertProblem(gone, 404, "course/not-found");
        assertThat(perform(null, get(path).header(HttpHeaders.IF_NONE_MATCH, "*"), null).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void problemsAndAuthenticatedResponsesAreNeverStoredByCaches() throws Exception {
        Account admin = account(Role.ADMIN);
        List<MvcResult> uncacheable = List.of(
                perform(null, get("/api/v1/public/courses/" + uniqueSlug("missing")), null),
                perform(null, get("/api/v1/public/courses").param("size", "0"), null),
                perform(null, get("/sitemap-courses-999.xml"), null),
                perform(admin, get("/api/v1/admin/accounts"), null),
                perform(admin, get("/api/v1/courses"), null));
        assertThat(uncacheable).extracting(r -> r.getResponse().getStatus()).containsExactly(404, 400, 404, 200, 200);
        assertThat(uncacheable).allSatisfy(r -> {
            assertThat(r.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store").doesNotContain("public");
            assertThat(r.getResponse().getHeader(HttpHeaders.ETAG)).isNull();
        });
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private Instant catalogRevision() {
        return catalogRevisionBean.current().orElseThrow();
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    private Instant siteFactsDateModified() throws Exception {
        return Instant.parse(body(perform(null, get("/api/v1/public/site-facts"), null)).get("dateModified").asText());
    }

    private Instant storedUpdatedAt(Course course) {
        return jdbc.queryForObject("SELECT updated_at FROM course WHERE id = ?", Timestamp.class, course.getId())
                .toInstant();
    }

    private static String w3c(Instant instant) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC));
    }

    private static void assertCachingHeaders(MvcResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=300, public");
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).matches("\"[0-9a-f]{32}\"");
        assertThat(result.getResponse().getHeader(HttpHeaders.PRAGMA)).isNull();
    }

    private void assertProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(body(result).get("code").asText()).isEqualTo(code);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode itemWithSlug(JsonNode page, String slug) {
        for (JsonNode item : page.get("content")) {
            if (slug.equals(item.get("slug").asText())) {
                return item;
            }
        }
        throw new AssertionError("no item with slug " + slug);
    }

    private static Map<String, String> parseSitemap(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        Element root = document.getDocumentElement();
        assertThat(root.getLocalName()).isEqualTo("urlset");
        assertThat(root.getNamespaceURI()).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9");
        Map<String, String> lastmodByLoc = new HashMap<>();
        NodeList urls = root.getElementsByTagNameNS(root.getNamespaceURI(), "url");
        for (int i = 0; i < urls.getLength(); i++) {
            Element url = (Element) urls.item(i);
            String loc = url.getElementsByTagNameNS(root.getNamespaceURI(), "loc").item(0).getTextContent();
            NodeList lastmod = url.getElementsByTagNameNS(root.getNamespaceURI(), "lastmod");
            assertThat(lastmodByLoc).as("duplicate loc %s", loc).doesNotContainKey(loc);
            lastmodByLoc.put(loc, lastmod.getLength() == 0 ? null : lastmod.item(0).getTextContent());
        }
        return lastmodByLoc;
    }
}
