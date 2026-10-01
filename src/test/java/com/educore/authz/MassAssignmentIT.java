package com.educore.authz;

import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.entity.IpBlock;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** {@code id}, {@code role}, {@code deleted} and other server-owned fields in request bodies are ignored. */
class MassAssignmentIT extends AuthzIntegrationSupport {

    @Test
    void createStudentIgnoresIdRoleDeletedAndCredentialFields() throws Exception {
        Account admin = account(Role.ADMIN);
        Account victim = account(Role.USER);
        String studentNumber = uniqueStudentNumber();
        cleanUpStudentNumber(studentNumber);

        MvcResult result = perform(admin, post("/api/v1/admin/accounts/students"), map(
                "id", victim.getId(), "firstName", "Mass", "lastName", "Assigned", "studentNumber", studentNumber,
                "role", "ADMIN", "deleted", 1, "mustChangePassword", false,
                /* TEST DATA ONLY */ "password", "mass-assignment-it-only-value"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = body(result);
        assertThat(body.get("id").asLong()).isNotEqualTo(victim.getId());
        assertThat(body.get("role").asText()).isEqualTo("USER");
        Account created = accountRepository.findById(body.get("id").asLong()).orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.USER);
        assertThat(created.getDeleted()).isZero();
        assertThat(created.isMustChangePassword()).isTrue();
        assertThat(passwordEncoder.matches("mass-assignment-it-only-value", created.getPassword())).isFalse();
        Account untouched = accountRepository.findById(victim.getId()).orElseThrow();
        assertThat(untouched.getFirstName()).isEqualTo(victim.getFirstName());
        assertThat(untouched.getPassword()).isEqualTo(victim.getPassword());
    }

    @Test
    void updateAccountIgnoresIdRoleDeletedUsernameAndPassword() throws Exception {
        Account admin = account(Role.ADMIN);
        Account target = account(Role.USER);
        Account other = account(Role.USER);

        MvcResult result = perform(admin, put("/api/v1/admin/accounts/" + target.getId()), map(
                "id", other.getId(), "firstName", "Renamed", "lastName", "Target",
                "studentNumber", target.getStudentNumber(), "role", "ADMIN", "deleted", 1,
                "username", "hijacked-" + target.getId(), "password", "x", "mustChangePassword", true));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Account stored = accountRepository.findById(target.getId()).orElseThrow();
        assertThat(stored.getFirstName()).isEqualTo("Renamed");
        assertThat(stored.getRole()).isEqualTo(Role.USER);
        assertThat(stored.getDeleted()).isZero();
        assertThat(stored.getUsername()).isEqualTo(target.getUsername());
        assertThat(stored.getPassword()).isEqualTo(target.getPassword());
        assertThat(stored.isMustChangePassword()).isFalse();
        assertThat(accountRepository.findById(other.getId()).orElseThrow().getFirstName())
                .isEqualTo(other.getFirstName());
    }

    @Test
    void ownProfileUpdateChangesOnlyTheName() throws Exception {
        Account user = account(Role.USER);
        Account other = account(Role.USER);

        MvcResult result = perform(user, put("/api/v1/me"), map(
                "id", other.getId(), "firstName", "Self", "lastName", "Edited", "role", "ADMIN", "deleted", 1,
                "studentNumber", "0000", "ipAddress", "10.9.9.9", "username", "hijacked"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Account stored = accountRepository.findById(user.getId()).orElseThrow();
        assertThat(stored.getFirstName()).isEqualTo("Self");
        assertThat(stored.getLastName()).isEqualTo("Edited");
        assertThat(stored.getRole()).isEqualTo(Role.USER);
        assertThat(stored.getDeleted()).isZero();
        assertThat(stored.getStudentNumber()).isEqualTo(user.getStudentNumber());
        assertThat(stored.getIpAddress()).isNull();
        assertThat(stored.getUsername()).isEqualTo(user.getUsername());
        assertThat(accountRepository.findById(other.getId()).orElseThrow().getFirstName())
                .isEqualTo(other.getFirstName());
    }

    @Test
    void courseBodiesCannotRetargetAnotherCourse() throws Exception {
        Account admin = account(Role.ADMIN);
        Course existing = course();
        Course edited = course();
        String createdName = "authz-mass-" + existing.getId();
        cleanUpCourseName(createdName);
        String renamed = "authz-mass-renamed-" + edited.getId();

        MvcResult created = perform(admin, post("/api/v1/admin/courses"),
                map("id", existing.getId(), "name", createdName, "term", "2026/1"));
        MvcResult updated = perform(admin, put("/api/v1/admin/courses/" + edited.getId()),
                map("id", existing.getId(), "name", renamed, "term", "2026/2"));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(created).get("id").asLong()).isNotEqualTo(existing.getId());
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(courseRepository.findById(edited.getId()).orElseThrow().getName()).isEqualTo(renamed);
        assertThat(courseRepository.findById(existing.getId()).orElseThrow().getName())
                .isEqualTo(existing.getName());
    }

    @Test
    void ipRuleBoundsAreAlwaysComputedByTheServer() throws Exception {
        Account admin = account(Role.ADMIN);
        String value = "198.51.100.0/30";
        cleanUpIpRuleValue(value);

        MvcResult result = perform(admin, post("/api/v1/admin/ip-rules"),
                map("id", 1, "type", "CIDR", "originalValue", value, "startIp", 0, "endIp", 4294967295L));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        IpBlock stored = ipBlockRepository.findById(body(result).get("id").asLong()).orElseThrow();
        assertThat(stored.getStartIp()).isEqualTo(0xC6336400L);
        assertThat(stored.getEndIp()).isEqualTo(0xC6336403L);
        assertThat(body(result).has("startIp")).isFalse();
    }

    @Test
    void ownEnrollmentIgnoresAnAccountIdInTheBody() throws Exception {
        Account user = account(Role.USER);
        Account other = account(Role.USER);
        Course course = course();

        MvcResult result = perform(user, post("/api/v1/me/enrollments"),
                map("accountId", other.getId(), "courseId", course.getId()));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(enrollmentRepository.existsByAccountIdAndCourseId(user.getId(), course.getId())).isTrue();
        assertThat(enrollmentRepository.existsByAccountIdAndCourseId(other.getId(), course.getId())).isFalse();
    }

    @Test
    void responsesNeverCarryPasswordsOrInternalFlags() throws Exception {
        Account admin = account(Role.ADMIN);
        Account user = account(Role.USER);

        for (MvcResult result : new MvcResult[]{
                perform(admin, get("/api/v1/admin/accounts").param("search", user.getStudentNumber()), null),
                perform(admin, get("/api/v1/admin/accounts/students").param("search", user.getStudentNumber()), null),
                perform(admin, put("/api/v1/admin/accounts/" + user.getId()), map("firstName", "A", "lastName", "B",
                        "studentNumber", user.getStudentNumber())),
                perform(user, get("/api/v1/me"), null)}) {
            String text = result.getResponse().getContentAsString();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(text).contains(String.valueOf(user.getId()))
                    .doesNotContain("password").doesNotContain("$2a$").doesNotContain("deleted")
                    .doesNotContain("mustChangePassword").doesNotContain("authorities");
        }
    }
}
