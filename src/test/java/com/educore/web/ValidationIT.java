package com.educore.web;

import com.educore.authz.AuthzIntegrationSupport;
import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Every request DTO and every validated query/path parameter rejects invalid input with 400
 * {@code application/problem+json}, {@code errors[{field, code}]} and no echo of the submitted value.
 */
class ValidationIT extends AuthzIntegrationSupport {

    private static final String SECRET_VALUE = "Do-Not-Echo-This-Value-7Q";

    // ---- request bodies ----------------------------------------------------------------------------------

    @Test
    void loginRequest() throws Exception {
        MvcResult blank = perform(null, post("/api/v1/auth/login"), map("username", "", "password", SECRET_VALUE));
        assertInvalid(blank, "auth/invalid-request", "username:required");

        MvcResult tooLong = perform(null, post("/api/v1/auth/login"),
                map("username", "someone", "password", SECRET_VALUE + "x".repeat(130)));
        assertInvalid(tooLong, "auth/invalid-request", "password:size");

        MvcResult control = perform(null, post("/api/v1/auth/login"), map("username", "a\nb", "password", "x"));
        assertInvalid(control, "auth/invalid-request", "username:pattern");
    }

    @Test
    void passwordChangeRequest() throws Exception {
        Account user = account(Role.USER);

        MvcResult result = perform(user, post("/api/v1/auth/password"),
                map("currentPassword", SECRET_VALUE, "newPassword", ""));

        assertInvalid(result, "auth/invalid-request", "newPassword:required");
    }

    @Test
    void updateProfileRequest() throws Exception {
        Account user = account(Role.USER);

        MvcResult markup = perform(user, put("/api/v1/me"), map("firstName", "<script>", "lastName", ""));
        assertInvalid(markup, "request/invalid", "firstName:pattern", "lastName:required");

        MvcResult tooLong = perform(user, put("/api/v1/me"), map("firstName", "A".repeat(101), "lastName", "Valid"));
        assertInvalid(tooLong, "request/invalid", "firstName:size");
    }

    @Test
    void createStudentRequest() throws Exception {
        Account admin = account(Role.ADMIN);

        MvcResult result = perform(admin, post("/api/v1/admin/accounts/students"), map(
                "firstName", "Valid", "lastName", "Name1", "studentNumber", "12ab",
                "username", "a b", "ipAddress", "999.1.1.1"));
        assertInvalid(result, "request/invalid",
                "ipAddress:pattern", "lastName:pattern", "studentNumber:pattern", "username:pattern");

        MvcResult shortNumber = perform(admin, post("/api/v1/admin/accounts/students"),
                map("firstName", "Valid", "studentNumber", "123"));
        assertInvalid(shortNumber, "request/invalid", "studentNumber:pattern");

        MvcResult longNumber = perform(admin, post("/api/v1/admin/accounts/students"),
                map("firstName", "Valid", "studentNumber", "1234567890123"));
        assertInvalid(longNumber, "request/invalid", "studentNumber:pattern");

        MvcResult missingName = perform(admin, post("/api/v1/admin/accounts/students"),
                map("studentNumber", uniqueStudentNumber()));
        assertInvalid(missingName, "request/invalid", "firstName:required");
    }

    @Test
    void createStudentAcceptsUnicodeNamesAndBoundaryStudentNumbers() throws Exception {
        Account admin = account(Role.ADMIN);
        for (String number : List.of("4" + uniqueStudentNumber().substring(0, 3), "9" + uniqueStudentNumber() + "1")) {
            cleanUpStudentNumber(number);
            MvcResult created = perform(admin, post("/api/v1/admin/accounts/students"),
                    map("firstName", "Ayşe Nur", "lastName", "O'Neil-Öztürk", "studentNumber", number));
            assertThat(created.getResponse().getStatus()).as("student number %s", number).isEqualTo(201);
        }
    }

    @Test
    void updateStudentRequest() throws Exception {
        Account admin = account(Role.ADMIN);
        Account student = account(Role.USER);
        String path = "/api/v1/admin/accounts/" + student.getId();

        MvcResult result = perform(admin, put(path), map("firstName", " ", "lastName", "Name",
                "studentNumber", student.getStudentNumber(), "ipAddress", "1.2.3"));
        assertInvalid(result, "request/invalid", "firstName:pattern", "firstName:required", "ipAddress:pattern");

        MvcResult emptyOptionals = perform(admin, put(path), map("firstName", "Valid", "lastName", "",
                "studentNumber", "", "ipAddress", ""));
        assertThat(emptyOptionals.getResponse().getStatus()).as("empty optional fields mean 'none'").isEqualTo(200);
    }

    @Test
    void changeRoleRequest() throws Exception {
        Account admin = account(Role.ADMIN);
        Account student = account(Role.USER);
        String path = "/api/v1/admin/accounts/" + student.getId() + "/role";

        assertInvalid(perform(admin, put(path), map("role", "ROOT")), "request/invalid", "role:enum");
        assertInvalid(perform(admin, put(path), map("role", null)), "request/invalid", "role:required");
        assertInvalid(perform(admin, put(path), map("role", List.of("ADMIN"))), "request/invalid", "role:type");
        assertThat(accountRepository.findById(student.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void courseRequest() throws Exception {
        Account admin = account(Role.ADMIN);

        MvcResult result = perform(admin, post("/api/v1/admin/courses"),
                map("name", "N".repeat(151), "term", "2026\n1", "instructor", "I".repeat(101)));
        assertInvalid(result, "request/invalid", "instructor:size", "name:size", "term:pattern");

        MvcResult blank = perform(admin, post("/api/v1/admin/courses"), map("name", "  "));
        assertInvalid(blank, "request/invalid", "name:required");
    }

    @Test
    void enrollRequest() throws Exception {
        Account user = account(Role.USER);

        assertInvalid(perform(user, post("/api/v1/me/enrollments"), map("courseId", 0)),
                "request/invalid", "courseId:range");
        assertInvalid(perform(user, post("/api/v1/me/enrollments"), map("courseId", -4)),
                "request/invalid", "courseId:range");
        assertInvalid(perform(user, post("/api/v1/me/enrollments"), map("courseId", "abc")),
                "request/invalid", "courseId:type");
        assertInvalid(perform(user, post("/api/v1/me/enrollments"), map()),
                "request/invalid", "courseId:required");
    }

    @Test
    void ipRuleRequest() throws Exception {
        Account admin = account(Role.ADMIN);

        assertInvalid(perform(admin, post("/api/v1/admin/ip-allocations"), map("type", "BOGUS", "originalValue", "10.0.0.1")),
                "request/invalid", "type:enum");
        for (String value : List.of("10.0.0.1/33", "10.0.0", "256.1.1.1", "10.0.0.1-", "10.0.0.1 OR 1=1",
                "010.0.0.1")) {
            assertInvalid(perform(admin, post("/api/v1/admin/ip-allocations"), map("type", "CIDR", "originalValue", value)),
                    "request/invalid", "originalValue:pattern");
        }
        assertThat(perform(admin, post("/api/v1/admin/ip-allocations"), map("type", "STATIC", "originalValue", "10.0.0.0/8"))
                .getResponse().getStatus()).as("well-formed value of the wrong type").isEqualTo(400);
    }

    @Test
    void malformedAndMissingBodies() throws Exception {
        Account admin = account(Role.ADMIN);

        MvcResult malformed = perform(admin, post("/api/v1/admin/courses")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"x\""), null);
        assertInvalid(malformed, "request/invalid", "body:malformed");

        MvcResult missing = perform(admin, post("/api/v1/admin/courses").contentType(MediaType.APPLICATION_JSON), null);
        assertInvalid(missing, "request/invalid", "body:required");

        MvcResult wrongShape = perform(admin, post("/api/v1/admin/courses")
                .contentType(MediaType.APPLICATION_JSON).content("[1,2]"), null);
        assertInvalid(wrongShape, "request/invalid", "body:type");
    }

    // ---- strict JSON binding (no coercion) ---------------------------------------------------------------

    @Test
    void numbersAreNotAcceptedForEnums() throws Exception {
        Account admin = account(Role.ADMIN);
        Account student = account(Role.USER);
        String path = "/api/v1/admin/accounts/" + student.getId() + "/role";

        for (Object value : List.of(0, 1, "0")) {
            MvcResult result = perform(admin, put(path), map("role", value));
            assertThat(result.getResponse().getStatus()).as("role %s", value).isEqualTo(400);
            assertThat(errors(body(result))).as("role %s", value).hasSize(1).allMatch(error -> error.startsWith("role:"));
        }
        assertThat(perform(admin, post("/api/v1/admin/ip-allocations"), map("type", 2, "originalValue", "10.0.0.0/8"))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(accountRepository.findById(student.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void fractionsStringsAndBooleansAreNotCoercedToNumbers() throws Exception {
        Account user = account(Role.USER);
        Course course = course();

        assertInvalid(perform(user, post("/api/v1/me/enrollments"), map("courseId", course.getId() + 0.9)),
                "request/invalid", "courseId:type");
        assertInvalid(perform(user, post("/api/v1/me/enrollments"), map("courseId", String.valueOf(course.getId()))),
                "request/invalid", "courseId:type");
        assertInvalid(perform(user, post("/api/v1/me/enrollments"), map("courseId", true)),
                "request/invalid", "courseId:type");
        assertThat(enrollmentRepository.existsByAccountIdAndCourseId(user.getId(), course.getId())).isFalse();
    }

    @Test
    void numbersAndBooleansAreNotCoercedToStrings() throws Exception {
        Account user = account(Role.USER);

        assertInvalid(perform(user, put("/api/v1/me"), map("firstName", 12345, "lastName", "Valid")),
                "request/invalid", "firstName:type");
        assertInvalid(perform(user, put("/api/v1/me"), map("firstName", "Valid", "lastName", false)),
                "request/invalid", "lastName:type");
    }

    @Test
    void trailingContentAfterTheJsonValueIsRejected() throws Exception {
        Account user = account(Role.USER);

        MvcResult result = perform(user, put("/api/v1/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Valid\",\"lastName\":\"Name\"} {\"firstName\":\"Other\"}"), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(errors(body(result))).hasSize(1).allMatch(error -> error.startsWith("body:"));
        assertThat(accountRepository.findById(user.getId()).orElseThrow().getFirstName()).isEqualTo("Fixture");
    }

    // ---- query and path parameters -----------------------------------------------------------------------

    @Test
    void pageOffsetBeyondTheIntRangeIsRejected() throws Exception {
        Account admin = account(Role.ADMIN);
        int lastPage = Integer.MAX_VALUE / 100;

        assertThat(perform(admin, get("/api/v1/admin/accounts").param("page", String.valueOf(lastPage))
                .param("size", "100"), null).getResponse().getStatus()).isEqualTo(200);
        assertInvalid(perform(admin, get("/api/v1/admin/accounts").param("page", String.valueOf(lastPage + 1))
                .param("size", "100"), null), "request/invalid", "page:range");
        assertInvalid(perform(admin, get("/api/v1/admin/security-events").param("page", "2147483647")
                .param("size", "2"), null), "request/invalid", "page:range");
    }


    @Test
    void pagingParameters() throws Exception {
        Account admin = account(Role.ADMIN);
        for (String route : List.of("/api/v1/admin/accounts", "/api/v1/admin/accounts/students",
                "/api/v1/admin/security-events")) {
            assertInvalid(perform(admin, get(route).param("page", "-1"), null), "request/invalid", "page:range");
            assertInvalid(perform(admin, get(route).param("size", "101"), null), "request/invalid", "size:range");
            assertInvalid(perform(admin, get(route).param("size", "0"), null), "request/invalid", "size:range");
            assertInvalid(perform(admin, get(route).param("size", "ten"), null), "request/invalid", "size:type");
            assertThat(perform(admin, get(route).param("size", "100"), null).getResponse().getStatus())
                    .as("%s size=100", route).isEqualTo(200);
        }
    }

    @Test
    void searchAndFlagParameters() throws Exception {
        Account admin = account(Role.ADMIN);

        assertInvalid(perform(admin, get("/api/v1/admin/accounts").param("search", "x".repeat(101)), null),
                "request/invalid", "search:size");
        assertInvalid(perform(admin, get("/api/v1/admin/accounts/students").param("search", "a\u0000b"), null),
                "request/invalid", "search:pattern");
        assertInvalid(perform(admin, get("/api/v1/admin/accounts").param("deleted", "maybe"), null),
                "request/invalid", "deleted:type");
    }

    @Test
    void sortParameters() throws Exception {
        Account admin = account(Role.ADMIN);
        for (String route : List.of("/api/v1/admin/accounts", "/api/v1/admin/accounts/students")) {
            for (String[] params : List.of(new String[]{"sort", "password"}, new String[]{"sort", "role"},
                    new String[]{"sort", "firstName;DROP TABLE account"}, new String[]{"direction", "sideways"},
                    new String[]{"direction", "desc nulls first"})) {
                MvcResult result = perform(admin, get(route).param(params[0], params[1]), null);
                assertThat(result.getResponse().getStatus()).as("%s %s=%s", route, params[0], params[1]).isEqualTo(400);
                assertThat(body(result).get("code").asText()).isEqualTo("sort/invalid");
                assertThat(result.getResponse().getContentAsString()).doesNotContain(params[1]);
            }
        }
    }

    @Test
    void pathVariables() throws Exception {
        Account admin = account(Role.ADMIN);
        Account user = account(Role.USER);

        assertInvalid(perform(admin, delete("/api/v1/admin/ip-allocations/0"), null), "request/invalid",
                "ipAllocationId:range");
        assertInvalid(perform(admin, delete("/api/v1/admin/ip-rules/0"), null), "request/invalid", "ipRuleId:range");
        assertInvalid(perform(admin, put("/api/v1/admin/accounts/-5/role"), map("role", "USER")),
                "request/invalid", "accountId:range");
        assertInvalid(perform(admin, get("/api/v1/admin/accounts/abc/enrollments"), null),
                "request/invalid", "accountId:type");
        assertInvalid(perform(admin, delete("/api/v1/admin/courses/0"), null), "request/invalid", "courseId:range");
        assertInvalid(perform(user, delete("/api/v1/me/enrollments/0"), null), "request/invalid", "courseId:range");
    }

    @Test
    void jobLogIds() throws Exception {
        Account admin = account(Role.ADMIN);

        assertInvalid(perform(admin, delete("/api/v1/admin/job-logs"), null), "request/invalid", "ids:required");
        assertInvalid(perform(admin, delete("/api/v1/admin/job-logs").param("ids", "0"), null),
                "request/invalid", "ids:range");
        assertInvalid(perform(admin, delete("/api/v1/admin/job-logs").param("ids", "1,x"), null),
                "request/invalid", "ids:type");
        String tooMany = IntStream.rangeClosed(1, 501).mapToObj(Integer::toString).collect(Collectors.joining(","));
        assertInvalid(perform(admin, delete("/api/v1/admin/job-logs").param("ids", tooMany), null),
                "request/invalid", "ids:size");
    }

    @Test
    void validRequestsStillSucceed() throws Exception {
        Account admin = account(Role.ADMIN);
        Course course = course();
        String name = "validation-course-" + course.getId();
        cleanUpCourseName(name);

        assertThat(perform(admin, put("/api/v1/admin/courses/" + course.getId()),
                map("name", name, "term", "2026/1", "instructor", "Dr. Ayşe Öz")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(perform(admin, post("/api/v1/admin/accounts/" + admin.getId() + "/enrollments"),
                map("courseId", course.getId())).getResponse().getStatus()).isEqualTo(201);
        assertThat(perform(admin, get("/api/v1/admin/accounts/students")
                .param("sort", "studentNumber").param("direction", "DESC").param("page", "0").param("size", "5"), null)
                .getResponse().getStatus()).isEqualTo(200);
    }

    // ---- assertions --------------------------------------------------------------------------------------

    private void assertInvalid(MvcResult result, String code, String... expectedErrors) throws Exception {
        String raw = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(raw).isEqualTo(400);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = body(result);
        assertThat(problem.get("code").asText()).isEqualTo(code);
        assertThat(problem.get("type").asText()).isEqualTo("/problems/" + code);
        assertThat(errors(problem)).containsExactlyInAnyOrder(expectedErrors);
        assertThat(raw).doesNotContain(SECRET_VALUE).doesNotContain("Exception").doesNotContain("rejected");
    }

    private static List<String> errors(JsonNode problem) {
        List<String> errors = new ArrayList<>();
        problem.path("errors").forEach(error -> errors.add(error.get("field").asText() + ":" + error.get("code").asText()));
        return errors;
    }
}
