package com.educore.publicapi;

import com.educore.entity.Account;
import com.educore.entity.AccountStatus;
import com.educore.entity.Course;
import com.educore.entity.Enrollment;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.repository.EnrollmentRepository;
import com.educore.common.web.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Data-exposure guard of the anonymous public surface ({@code /api/v1/public/**} and {@code /sitemap.xml}).
 * <p>
 * Attack: an anonymous caller (or a crawler) reads the public catalog hoping to harvest personal data —
 * account ids, usernames, names, student numbers, e-mail addresses, password hashes, roles, soft-delete flags
 * or who is enrolled in which course — either because a response type grows a field, because an entity or
 * entity graph is serialised, or because a query joins enrollments.
 * <p>
 * Defence, proven twice: (1) by reflection, every public handler returns only record DTOs whose (transitive)
 * components are plain values with no account-related name, no entity type and no collection of entities;
 * the repository behind them returns DTO projections only; and no public API class depends on the account or
 * enrollment model. (2) by behaviour, a populated dataset (published courses with enrolled students and an
 * ADMIN) is read through every public endpoint, and the serialised JSON and the sitemap contain no forbidden
 * field name and none of the accounts' values.
 */
class PublicApiLeakIT extends PublicApiTestSupport {

    /** The complete serialised schema of every public DTO: exactly these members, in this order. */
    private static final List<String> COURSE_FIELDS =
            List.of("name", "slug", "term", "instructor", "description", "updatedAt");
    private static final List<String> SITE_FACTS_FIELDS =
            List.of("name", "baseUrl", "description", "languages", "dateModified");
    private static final List<String> PAGE_FIELDS =
            List.of("content", "page", "size", "totalElements", "totalPages");
    private static final Map<Class<?>, List<String>> SCHEMAS = Map.of(
            PublicCourseResponse.class, COURSE_FIELDS,
            SiteFactsResponse.class, SITE_FACTS_FIELDS,
            PageResponse.class, PAGE_FIELDS);

    /**
     * Members that must never appear anywhere in a public document (already excluded by the exact schemas;
     * listed so a schema change that adds one of them fails with an explicit message).
     */
    private static final Set<String> NEVER = Set.of("id", "courseId", "accountId", "userId", "createdBy",
            "created_by", "updatedBy", "username", "password", "email", "studentNumber", "firstName", "lastName",
            "role", "roles", "deleted", "published", "version", "ipAddress", "enrollments", "students", "accounts");

    private static final Set<Class<?>> LEAF_TYPES = Set.of(String.class, Instant.class, int.class, long.class,
            boolean.class, Integer.class, Long.class, Boolean.class);

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private ObjectMapper applicationMapper;

    @Autowired
    private EnrollmentRepository enrollments;

    // ---- (1) reflection -----------------------------------------------------------------------------------

    @Test
    void publicHandlersReturnOnlyRecordDtosWithoutAccountFields() {
        Map<String, HandlerMethod> handlers = publicHandlers();
        assertThat(handlers.keySet()).containsExactlyInAnyOrder("GET /api/v1/public/courses",
                "GET /api/v1/public/courses/{slug}", "GET /api/v1/public/site-facts", "GET /sitemap.xml",
                "GET /sitemap-courses-{file}.xml");

        Set<Class<?>> dtos = new LinkedHashSet<>();
        handlers.forEach((key, handler) -> {
            ResolvableType returnType = ResolvableType.forMethodReturnType(handler.getMethod());
            assertThat(returnType.toClass()).as("%s returns a ResponseEntity", key).isEqualTo(ResponseEntity.class);
            collectTypes(key, returnType.getGeneric(0), dtos);
        });

        assertThat(dtos).containsExactlyInAnyOrderElementsOf(SCHEMAS.keySet());
        for (Class<?> dto : dtos) {
            assertThat(dto.isRecord()).as("%s is a record", dto.getName()).isTrue();
            assertThat(dto.getPackageName()).as(dto.getName()).isNotEqualTo("com.educore.entity");
            assertThat(Arrays.stream(dto.getRecordComponents()).map(RecordComponent::getName).toList())
                    .as("components of %s", dto.getSimpleName()).containsExactlyElementsOf(SCHEMAS.get(dto));
        }
    }

    /**
     * What Jackson actually writes (extra getters, {@code @JsonProperty}, mix-ins or naming strategies of the
     * application ObjectMapper included) is exactly the record schema.
     */
    @Test
    void theApplicationObjectMapperWritesExactlyTheSchema() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        PublicCourseResponse course = new PublicCourseResponse("n", "s", "t", "i", "d", now);
        SiteFactsResponse facts = new SiteFactsResponse("n", "https://educore.example", "d", List.of("tr"), now);
        PageResponse<PublicCourseResponse> page = new PageResponse<>(List.of(course), 0, 1, 1, 1);

        assertThat(fieldNames(applicationMapper.valueToTree(course))).containsExactlyElementsOf(COURSE_FIELDS);
        assertThat(fieldNames(applicationMapper.valueToTree(facts))).containsExactlyElementsOf(SITE_FACTS_FIELDS);
        JsonNode pageJson = applicationMapper.valueToTree(page);
        assertThat(fieldNames(pageJson)).containsExactlyElementsOf(PAGE_FIELDS);
        assertThat(fieldNames(pageJson.get("content").get(0))).containsExactlyElementsOf(COURSE_FIELDS);
    }

    @Test
    void publicRepositoryReturnsProjectionsNeverEntities() {
        for (Method method : PublicCourseRepository.class.getDeclaredMethods()) {
            ResolvableType type = ResolvableType.forMethodReturnType(method);
            List<Class<?>> mentioned = new ArrayList<>();
            mentioned.add(type.toClass());
            for (ResolvableType generic : type.getGenerics()) {
                mentioned.add(generic.toClass());
            }
            assertThat(mentioned).as(method.getName())
                    .noneMatch(c -> c.getPackageName().equals("com.educore.entity"));
        }
    }

    @Test
    void publicApiCodeNeverTouchesTheAccountOrEnrollmentModel() {
        // DO_NOT_INCLUDE_TESTS only recognises target/; the build directory is configurable, so exclude any
        // test-classes directory explicitly.
        ImportOption mainClassesOnly = location -> !location.contains("/test-classes/");
        JavaClasses publicApi = new ClassFileImporter().withImportOption(mainClassesOnly)
                .importPackages("com.educore.publicapi");
        assertThat(publicApi.contain(PublicApiLeakIT.class)).isFalse();
        assertThat(publicApi).isNotEmpty();
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName(Account.class.getName())
                .orShould().dependOnClassesThat().haveFullyQualifiedName(Enrollment.class.getName())
                .orShould().dependOnClassesThat().haveFullyQualifiedName(Role.class.getName())
                .orShould().dependOnClassesThat().haveFullyQualifiedName(AccountRepository.class.getName())
                .orShould().dependOnClassesThat().haveFullyQualifiedName(EnrollmentRepository.class.getName())
                .orShould().dependOnClassesThat().resideInAPackage("com.educore.account..")
                .orShould().dependOnClassesThat().resideInAPackage("com.educore.enrollment..")
                .check(publicApi);
    }

    // ---- (2) serialised responses of a populated dataset ----------------------------------------------------

    @Test
    void responsesOfAPopulatedDatasetContainNoAccountDataAndNoEnrollments() throws Exception {
        List<Course> published = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            published.add(publishedCourse(uniqueSlug("leak" + i), "Public summary " + i + "."));
        }
        Course draft = unpublishedCourse(uniqueSlug("leak-draft"), "Draft summary.");

        List<String> secrets = new ArrayList<>();
        List<Account> people = new ArrayList<>(List.of(account(Role.ADMIN), account(Role.USER), account(Role.USER)));
        Account softDeleted = account(Role.USER);
        softDeleted.setStatus(AccountStatus.DEACTIVATED);
        people.add(softDeleted);
        for (Account person : people) {
            String marker = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            Account stored = accounts.findById(person.getId()).orElseThrow();
            stored.setFirstName("Leakfirst" + marker);
            stored.setLastName("Leaklast" + marker);
            stored.setStatus(person.getStatus());
            stored = accounts.saveAndFlush(stored);
            secrets.addAll(List.of(stored.getUsername(), stored.getFirstName(), stored.getLastName(),
                    stored.getStudentNumber(), stored.getPassword()));
            for (Course course : published) {
                enroll(stored, course);
            }
            enroll(stored, draft);
        }
        assertThat(enrollments.count()).isGreaterThanOrEqualTo((long) people.size() * (published.size() + 1));

        List<MvcResult> responses = new ArrayList<>();
        for (String sort : List.of("name", "term", "updatedAt")) {
            for (String direction : List.of("asc", "desc")) {
                responses.add(perform(null, get("/api/v1/public/courses").param("size", "100")
                        .param("sort", sort).param("direction", direction), null));
            }
        }
        for (Course course : published) {
            responses.add(perform(null, get("/api/v1/public/courses/" + course.getSlug()), null));
            responses.add(perform(people.get(0), get("/api/v1/public/courses/" + course.getSlug()), null));
        }
        responses.add(perform(null, get("/api/v1/public/courses/" + draft.getSlug()), null));
        responses.add(perform(null, get("/api/v1/public/site-facts"), null));
        responses.add(perform(people.get(1), get("/api/v1/public/site-facts"), null));

        for (MvcResult result : responses) {
            String uri = result.getRequest().getRequestURI();
            String content = result.getResponse().getContentAsString();
            assertThat(result.getResponse().getStatus()).as(uri).isIn(200, 404);
            JsonNode tree = json.readTree(content);
            if (result.getResponse().getStatus() == 200) {
                assertExactSchema(uri, tree);
            }
            assertNeverPresent(uri, tree);
            assertNoSecrets(uri, content, secrets);
            assertNoEnrollmentShape(uri, tree);
        }

        String sitemap = perform(null, get("/sitemap.xml"), null).getResponse().getContentAsString();
        assertThat(sitemap).contains(published.get(0).getSlug());
        assertNoSecrets("/sitemap.xml", sitemap, secrets);
        assertThat(sitemap.toLowerCase(Locale.ROOT)).doesNotContain("student").doesNotContain("account")
                .doesNotContain("enrol");
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private Map<String, HandlerMethod> publicHandlers() {
        Map<String, HandlerMethod> handlers = new TreeMap<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            for (String pattern : info.getPatternValues()) {
                if (pattern.startsWith("/api/v1/public") || pattern.startsWith("/sitemap")) {
                    info.getMethodsCondition().getMethods()
                            .forEach(method -> handlers.put(method.name() + " " + pattern, handler));
                }
            }
        });
        return handlers;
    }

    /** Collects every application type reachable from {@code type}; leaves must be plain values. */
    private static void collectTypes(String path, ResolvableType type, Set<Class<?>> seen) {
        Class<?> raw = type.toClass();
        if (LEAF_TYPES.contains(raw)) {
            return;
        }
        if (List.class.isAssignableFrom(raw)) {
            collectTypes(path + "[]", type.getGeneric(0), seen);
            return;
        }
        assertThat(raw.getName()).as("%s: only records of the application may be serialised", path)
                .startsWith("com.educore.");
        assertThat(raw.isRecord()).as("%s: %s is a record", path, raw.getName()).isTrue();
        if (!seen.add(raw)) {
            return;
        }
        for (RecordComponent component : raw.getRecordComponents()) {
            ResolvableType componentType = ResolvableType.forType(component.getGenericType(), type);
            collectTypes(path + "." + component.getName(), componentType, seen);
        }
    }

    /** The document is exactly a page of courses, a course or the site facts. */
    private static void assertExactSchema(String uri, JsonNode node) {
        if (uri.endsWith("/site-facts")) {
            assertThat(fieldNames(node)).as(uri).containsExactlyElementsOf(SITE_FACTS_FIELDS);
            node.get("languages").forEach(language -> assertThat(language.isTextual()).as(uri).isTrue());
        } else if (uri.endsWith("/api/v1/public/courses")) {
            assertThat(fieldNames(node)).as(uri).containsExactlyElementsOf(PAGE_FIELDS);
            node.get("content").forEach(course -> assertCourse(uri, course));
        } else {
            assertCourse(uri, node);
        }
    }

    /** Exactly the course schema, and only text values: no number (no id) and no nested data. */
    private static void assertCourse(String uri, JsonNode course) {
        assertThat(fieldNames(course)).as(uri).containsExactlyElementsOf(COURSE_FIELDS);
        course.forEach(value -> assertThat(value.isTextual() || value.isNull())
                .as("%s: only text values in a course", uri).isTrue());
    }

    private static void assertNeverPresent(String uri, JsonNode node) {
        if (node.isObject()) {
            fieldNames(node).forEach(name -> assertThat(NEVER).as("%s member %s", uri, name).doesNotContain(name));
            node.forEach(value -> assertNeverPresent(uri, value));
        } else if (node.isArray()) {
            node.forEach(item -> assertNeverPresent(uri, item));
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertNoSecrets(String uri, String content, List<String> secrets) {
        for (String secret : secrets) {
            assertThat(content).as("%s must not contain account data", uri).doesNotContain(secret);
        }
    }

    /**
     * No enrollment data: no field whose name mentions enrollments or students anywhere in the document, and no
     * nested object or array inside a course (where a list of enrolled accounts would have to appear). Free text
     * such as the site description may mention enrollment as a feature; field names and structure may not.
     */
    private static void assertNoEnrollmentShape(String uri, JsonNode node) {
        assertNeverPresent(uri, node);
        JsonNode courses = node.has("content") ? node.get("content") : null;
        if (courses != null) {
            courses.forEach(course -> course.forEach(value ->
                    assertThat(value.isContainerNode()).as("%s: nested data in a course", uri).isFalse()));
        } else if (node.has("slug")) {
            node.forEach(value -> assertThat(value.isContainerNode()).as("%s: nested data in a course", uri).isFalse());
        }
    }
}
