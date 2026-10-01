package com.educore.authz;

import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Executes every cell of the machine-readable matrix {@code src/test/resources/rbac-matrix.csv}, which
 * {@code docs/security/RBAC_MATRIX.md} mirrors (application port; the management port rows are covered by
 * {@code ManagementEndpointSecurityIT}), and checks that the matrix matches the mapped handlers. Each cell runs against fresh fixtures: a calling
 * ADMIN, a calling USER, another USER, and whatever course, enrollment, IP rule or job log the route needs.
 */
class AuthorizationMatrixIT extends AuthzIntegrationSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger IP_RULE_SEQUENCE = new AtomicInteger(1);

    enum Column { ADMIN, USER_SELF, USER_OTHER, ANONYMOUS }

    @FunctionalInterface
    interface RequestFactory {
        MockHttpServletRequestBuilder build(Cell cell) throws Exception;
    }

    /** One row of {@code src/test/resources/rbac-matrix.csv}. */
    record MatrixRow(String id, String kind, String method, String path, String admin, String userSelf,
                     String userOther, String anonymous) {

        String cell(Column column) {
            return switch (column) {
                case ADMIN -> admin;
                case USER_SELF -> userSelf;
                case USER_OTHER -> userOther;
                case ANONYMOUS -> anonymous;
            };
        }

        int expected(Column column) {
            return Integer.parseInt(cell(column));
        }

        boolean testable(Column column) {
            return !"NA".equals(cell(column)) && !"OPEN".equals(cell(column));
        }

        boolean isEndpoint() {
            return "ENDPOINT".equals(kind);
        }

        boolean adminOnly() {
            return "403".equals(userSelf) && "403".equals(userOther) && "401".equals(anonymous);
        }

        boolean anonymousAllowed() {
            return anonymous.startsWith("2");
        }

        String key() {
            return method + " " + path;
        }

        @Override
        public String toString() {
            return id + " " + method + " " + path;
        }
    }

    /** Fixtures of one executed cell; everything created here is removed after the test. */
    final class Cell {
        private final Column column;
        private final Account admin = account(Role.ADMIN);
        private final Account user = account(Role.USER);
        private final Account other = account(Role.USER);

        Cell(Column column) {
            this.column = column;
        }

        /** The caller whose token is sent; {@code null} for anonymous. */
        Account caller() {
            return switch (column) {
                case ADMIN -> admin;
                case USER_SELF, USER_OTHER -> user;
                case ANONYMOUS -> null;
            };
        }

        /** The account in the path: the caller itself for USER-self, another USER otherwise. */
        Account target() {
            return column == Column.USER_SELF ? user : other;
        }

        /** The account a /me or session cell acts on: the caller, or another USER for anonymous. */
        Account self() {
            return caller() == null ? other : caller();
        }

        Course freshCourse() {
            return course();
        }

        /** A fresh course the given account is enrolled in. */
        Course enrolledCourse(Account account) {
            Course course = course();
            enroll(account, course);
            return course;
        }

        String newStudentNumber() {
            String studentNumber = uniqueStudentNumber();
            cleanUpStudentNumber(studentNumber);
            return studentNumber;
        }

        String newCourseName() {
            String name = "authz-course-" + UUID.randomUUID();
            cleanUpCourseName(name);
            return name;
        }

        String newIpRuleValue() {
            int n = IP_RULE_SEQUENCE.getAndIncrement();
            String value = "198.18." + ((n >> 8) & 0xff) + "." + (n & 0xff);
            cleanUpIpRuleValue(value);
            return value;
        }

        long ipRuleId() {
            return ipRule("198.19.0.0/24", 0xC6130000L, 0xC61300FFL).getId();
        }

        long jobLogId() {
            return jobLog().getId();
        }

        String refreshCookieOf(Account account) throws Exception {
            MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(from(newIp()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(map("username", account.getUsername(),
                                    "password", PASSWORD))))
                    .andReturn();
            assertThat(login.getResponse().getStatus()).isEqualTo(200);
            String header = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                    .filter(value -> value.startsWith("educore_rt=")).findFirst().orElseThrow();
            return header.substring("educore_rt=".length(), header.indexOf(';'));
        }
    }

    /** How to build a valid request for each matrix row id (fixtures come from the cell). */
    static Map<String, RequestFactory> factories() {
        Map<String, RequestFactory> routes = new LinkedHashMap<>();
        // 1-3: session endpoints, open to everyone.
        routes.put("01", cell -> jsonPost("/api/v1/auth/login",
                map("username", cell.self().getUsername(), "password", PASSWORD)));
        routes.put("02", cell -> post("/api/v1/auth/refresh")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(new Cookie("educore_rt", cell.refreshCookieOf(cell.self()))));
        routes.put("03", cell -> post("/api/v1/auth/logout")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN));
        // 4-12: any authenticated caller; /me routes always act on the caller.
        routes.put("04", cell -> get("/api/v1/auth/me"));
        routes.put("05", cell -> jsonPost("/api/v1/auth/password",
                map("currentPassword", PASSWORD, "newPassword", "Matrix-" + UUID.randomUUID())));
        routes.put("06", cell -> get("/api/v1/me"));
        routes.put("07", cell -> jsonPut("/api/v1/me",
                map("firstName", "New", "lastName", "Name")));
        routes.put("08", cell -> get("/api/v1/me/enrollments"));
        routes.put("09", cell -> jsonPost("/api/v1/me/enrollments",
                map("courseId", cell.freshCourse().getId())));
        routes.put("10", cell -> delete("/api/v1/me/enrollments/"
                + cell.enrolledCourse(cell.self()).getId()));
        routes.put("11", cell -> get("/api/v1/courses"));
        routes.put("12", cell -> get("/api/v1/weather"));
        // 13-30: ADMIN only.
        routes.put("13", cell -> get("/api/v1/admin/accounts"));
        routes.put("14", cell -> get("/api/v1/admin/accounts/students"));
        routes.put("15", cell -> jsonPost("/api/v1/admin/accounts/students",
                map("firstName", "Matrix", "lastName", "Student", "studentNumber", cell.newStudentNumber())));
        routes.put("16", cell -> jsonPut("/api/v1/admin/accounts/"
                + cell.target().getId(), map("firstName", "Changed", "lastName", "Name",
                "studentNumber", cell.target().getStudentNumber())));
        routes.put("17", cell -> delete("/api/v1/admin/accounts/"
                + cell.target().getId()));
        routes.put("18", cell -> jsonPut("/api/v1/admin/accounts/"
                + cell.target().getId() + "/role", map("role", "ADMIN")));
        routes.put("19", cell -> get("/api/v1/admin/accounts/"
                + cell.target().getId() + "/enrollments"));
        routes.put("20", cell -> jsonPost("/api/v1/admin/accounts/"
                + cell.target().getId() + "/enrollments", map("courseId", cell.freshCourse().getId())));
        routes.put("21", cell -> delete(
                "/api/v1/admin/accounts/" + cell.target().getId() + "/enrollments/"
                        + cell.enrolledCourse(cell.target()).getId()));
        routes.put("22", cell -> jsonPost("/api/v1/admin/courses",
                map("name", cell.newCourseName(), "term", "2026/2", "instructor", "Instructor Matrix")));
        routes.put("23", cell -> jsonPut("/api/v1/admin/courses/"
                + cell.freshCourse().getId(),
                map("name", cell.newCourseName(), "term", "2026/2", "instructor", "Instructor Matrix")));
        routes.put("24", cell -> delete("/api/v1/admin/courses/"
                + cell.freshCourse().getId()));
        routes.put("25", cell -> get("/api/v1/admin/ip-rules"));
        routes.put("26", cell -> jsonPost("/api/v1/admin/ip-rules",
                map("type", "STATIC", "originalValue", cell.newIpRuleValue())));
        routes.put("27", cell -> delete("/api/v1/admin/ip-rules/"
                + cell.ipRuleId()));
        routes.put("28", cell -> get("/api/v1/admin/job-logs"));
        routes.put("29", cell -> delete("/api/v1/admin/job-logs")
                .param("ids", String.valueOf(cell.jobLogId())));
        routes.put("30", cell -> get("/api/v1/admin/security-events"));
        // 31 (/api/v1/public/**) has no endpoint yet. 32: removed and unknown paths.
        routes.put("32", cell -> get("/api/v1/accounts/students"));
        return routes;
    }

    static List<MatrixRow> matrix() {
        try (InputStream in = AuthorizationMatrixIT.class.getResourceAsStream("/rbac-matrix.csv")) {
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#") && !line.startsWith("id,"))
                    .toList();
            return lines.stream().map(line -> line.split(",", -1)).map(f -> {
                assertThat(f).as("CSV row %s", String.join(",", f)).hasSize(8);
                return new MatrixRow(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7]);
            }).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Stream<Arguments> cells() {
        return matrix().stream().filter(row -> !"RESERVED".equals(row.kind()))
                .flatMap(row -> Stream.of(Column.values())
                        .filter(row::testable)
                        .map(column -> Arguments.of(row, column)));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("cells")
    void everyMatrixCellAnswersTheDocumentedStatus(MatrixRow row, Column column) throws Exception {
        RequestFactory factory = factories().get(row.id());
        assertThat(factory).as("request factory for row %s", row.id()).isNotNull();
        Cell cell = new Cell(column);

        MvcResult result = perform(cell.caller(), factory.build(cell), null);

        // The factory really exercises the row: same method, URI matching the mapping pattern.
        assertThat(result.getRequest().getMethod()).isEqualTo(row.method());
        if (row.isEndpoint()) {
            assertThat(result.getRequest().getRequestURI()).matches(row.path().replaceAll("\\{[^/]+}", "[^/]+"));
        }
        assertThat(result.getResponse().getStatus())
                .as("%s as %s: %s", row, column, result.getResponse().getContentAsString())
                .isEqualTo(row.expected(column));
    }

    @Test
    void factoriesAndMatrixRowsMatchOneToOne() {
        Set<String> testedRows = matrix().stream().filter(row -> !"RESERVED".equals(row.kind()))
                .map(MatrixRow::id).collect(Collectors.toCollection(TreeSet::new));
        assertThat(new TreeSet<>(factories().keySet())).isEqualTo(testedRows);
        assertThat(matrix().stream().map(MatrixRow::id).toList()).doesNotHaveDuplicates();
    }

    /** A new handler under /api/** without a matrix row (or a stale row without a handler) fails the build. */
    @Test
    void everyMappedApiHandlerHasExactlyOneMatrixRow() {
        Set<String> handlers = apiHandlers().keySet();
        Set<String> rows = matrix().stream().filter(MatrixRow::isEndpoint).map(MatrixRow::key)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(handlers).as("mapped /api/** handlers vs rbac-matrix.csv ENDPOINT rows").isEqualTo(rows);
    }

    /**
     * Every handler is protected the way its row says: ADMIN-only rows live under /api/v1/admin/ (URL rule)
     * AND carry {@code @PreAuthorize("hasRole('ADMIN')")}; anonymous rows are exactly the URL rule's permitAll
     * list; every other row is covered by {@code anyRequest().authenticated()} and is not an admin path.
     */
    @Test
    void everyHandlerIsProtectedAsItsMatrixRowRequires() {
        Map<String, MatrixRow> rows = matrix().stream().filter(MatrixRow::isEndpoint)
                .collect(Collectors.toMap(MatrixRow::key, row -> row));
        Set<String> anonymousUrlRules = Set.of("POST /api/v1/auth/login", "POST /api/v1/auth/refresh",
                "POST /api/v1/auth/logout");

        apiHandlers().forEach((key, handler) -> {
            MatrixRow row = rows.get(key);
            assertThat(row).as("matrix row for %s", key).isNotNull();
            PreAuthorize preAuthorize = preAuthorizeOf(handler);
            if (row.adminOnly()) {
                assertThat(row.path()).as(key).startsWith("/api/v1/admin/");
                assertThat(preAuthorize).as("@PreAuthorize on %s", key).isNotNull();
                assertThat(preAuthorize.value()).as(key).isEqualTo("hasRole('ADMIN')");
            } else if (row.anonymousAllowed()) {
                assertThat(anonymousUrlRules).as("anonymous URL rule for %s", key).contains(key);
            } else {
                assertThat(row.anonymous()).as(key).isEqualTo("401");
                assertThat(row.path()).as(key).doesNotStartWith("/api/v1/admin/");
                assertThat(preAuthorize).as("an authenticated-only route needs no role check: %s", key).isNull();
            }
        });
    }

    /** docs/security/RBAC_MATRIX.md mirrors the CSV row for row (same id, method, route and statuses). */
    @Test
    void theDocumentMirrorsTheMachineReadableMatrix() throws IOException {
        List<String> document = Files.readAllLines(Path.of("docs", "security", "RBAC_MATRIX.md"),
                StandardCharsets.UTF_8);
        List<String> tableRows = document.stream().filter(line -> line.matches("\\| \\d\\d \\|.*")).toList();

        assertThat(tableRows).hasSize(matrix().size());
        for (MatrixRow row : matrix()) {
            String expectedPrefix = "| " + row.id() + " | " + row.method() + " | `" + row.path() + "` | "
                    + row.admin() + " | " + row.userSelf() + " | " + row.userOther() + " | " + row.anonymous() + " |";
            assertThat(tableRows).as("document row for %s", row).anyMatch(line -> line.startsWith(expectedPrefix));
        }
    }

    private Map<String, HandlerMethod> apiHandlers() {
        Map<String, HandlerMethod> handlers = new TreeMap<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            Set<String> patterns = info.getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (pattern.startsWith("/api/")) {
                    assertThat(methods).as("every API handler declares its HTTP method: %s", handler).isNotEmpty();
                    methods.forEach(method -> handlers.put(method.name() + " " + pattern, handler));
                }
            }
        });
        return handlers;
    }

    private static PreAuthorize preAuthorizeOf(HandlerMethod handler) {
        PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        return onMethod != null ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
    }

    @Test
    void everyPreP3RouteIsGone() throws Exception {
        Account admin = account(Role.ADMIN);
        List<MockHttpServletRequestBuilder> removed = List.of(
                post("/api/v1/enroll"), delete("/api/v1/accounts/1/courses/1"), get("/api/v1/accounts/1/courses"),
                get("/api/v1/accounts/students"), get("/api/v1/accounts"), put("/api/v1/accounts/1/role"),
                post("/api/v1/accounts/student"), delete("/api/v1/accounts/1"), put("/api/v1/accounts/1"),
                post("/api/v1/courses"), delete("/api/v1/courses/1"), put("/api/v1/courses/1"),
                get("/api/v1/logs"), delete("/api/v1/logs"), get("/api/v1/ips"), get("/api/v1/ip-blocks"),
                delete("/api/v1/ip-blocks/1"), post("/api/v1/ip-blocks"), get("/api/weather"), get("/ws/students"));
        for (MockHttpServletRequestBuilder request : removed) {
            MvcResult asAdmin = perform(admin, request, null);
            // 405: the path survives for another method (GET /api/v1/courses is the read-only catalog).
            assertThat(asAdmin.getResponse().getStatus())
                    .as("%s %s as ADMIN", asAdmin.getRequest().getMethod(), asAdmin.getRequest().getRequestURI())
                    .isIn(404, 405);
        }
        assertThat(perform(null, get("/api/weather"), null).getResponse().getStatus()).isEqualTo(401);
        assertThat(perform(null, get("/ws/students"), null).getResponse().getStatus()).isEqualTo(401);
    }

    private static MockHttpServletRequestBuilder jsonPost(String path, Object body) throws Exception {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }

    private static MockHttpServletRequestBuilder jsonPut(String path, Object body) throws Exception {
        return put(path).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }
}
