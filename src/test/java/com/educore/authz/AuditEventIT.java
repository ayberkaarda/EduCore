package com.educore.authz;

import com.educore.entity.Account;
import com.educore.entity.AccountStatus;
import com.educore.entity.Course;
import com.educore.ipaccess.IpAllocationRange;
import com.educore.entity.JobLog;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Every ADMIN mutation writes exactly one {@code security_event} with actor, target, client IP and request
 * id; details carry ids and enum values only. Refused and no-op requests write nothing.
 */
class AuditEventIT extends AuthzIntegrationSupport {

    /** Columns of the single event written by one request, looked up by its request id. */
    record Event(String type, Long actor, Long target, String ip, JsonNode details) {
    }

    @Test
    void accountMutationsAreAudited() throws Exception {
        Account admin = account(Role.ADMIN);
        Account student = account(Role.USER);
        String studentNumber = uniqueStudentNumber();
        cleanUpStudentNumber(studentNumber);

        Audited created = audited(admin, post("/api/v1/admin/accounts/students"),
                map("firstName", "Audit", "lastName", "Created", "studentNumber", studentNumber));
        long createdId = body(created.result()).get("id").asLong();
        assertEvent(created, "ACCOUNT_CREATED", admin, createdId, Map.of("role", "USER"));

        Audited updated = audited(admin, put("/api/v1/admin/accounts/" + student.getId()),
                map("firstName", "Renamed", "lastName", student.getLastName(),
                        "studentNumber", student.getStudentNumber()));
        assertEvent(updated, "ACCOUNT_UPDATED", admin, student.getId(), Map.of("fields", List.of("firstName")));

        Audited promoted = audited(admin, put("/api/v1/admin/accounts/" + student.getId() + "/role"),
                map("role", "ADMIN"));
        assertEvent(promoted, "ROLE_CHANGED", admin, student.getId(), Map.of("from", "USER", "to", "ADMIN"));

        Audited deleted = audited(admin, delete("/api/v1/admin/accounts/" + student.getId()), null);
        assertEvent(deleted, "ACCOUNT_DELETED", admin, student.getId(), Map.of("soft", true));
    }

    @Test
    void courseIpRuleJobLogAndEnrollmentMutationsAreAudited() throws Exception {
        Account admin = account(Role.ADMIN);
        Account student = account(Role.USER);
        String name = "authz-audit-" + UUID.randomUUID();
        cleanUpCourseName(name);

        Audited courseCreated = audited(admin, post("/api/v1/admin/courses"), map("name", name, "term", "2026/1"));
        long courseId = body(courseCreated.result()).get("id").asLong();
        assertEvent(courseCreated, "COURSE_CHANGED", admin, null, Map.of("action", "CREATED", "courseId", courseId));
        assertEvent(audited(admin, put("/api/v1/admin/courses/" + courseId), map("name", name, "term", "2026/2")),
                "COURSE_CHANGED", admin, null, Map.of("action", "UPDATED", "courseId", courseId));

        assertEvent(audited(admin, post("/api/v1/admin/accounts/" + student.getId() + "/enrollments"),
                map("courseId", courseId)), "ENROLLMENT_CHANGED", admin, student.getId(),
                Map.of("action", "ENROLLED", "courseId", courseId));
        assertEvent(audited(admin, delete("/api/v1/admin/accounts/" + student.getId() + "/enrollments/" + courseId),
                null), "ENROLLMENT_CHANGED", admin, student.getId(), Map.of("action", "DROPPED", "courseId", courseId));

        assertEvent(audited(admin, delete("/api/v1/admin/courses/" + courseId), null),
                "COURSE_CHANGED", admin, null, Map.of("action", "DELETED", "courseId", courseId));

        String value = "198.51.100.77";
        cleanUpIpRuleValue(value);
        Audited ipCreated = audited(admin, post("/api/v1/admin/ip-allocations"),
                map("type", "STATIC", "originalValue", value));
        long ipAllocationId = body(ipCreated.result()).get("id").asLong();
        assertEvent(ipCreated, "IP_ALLOCATION_CHANGED", admin, null,
                Map.of("action", "CREATED", "ipAllocationId", ipAllocationId, "type", "STATIC"));
        assertEvent(audited(admin, delete("/api/v1/admin/ip-allocations/" + ipAllocationId), null),
                "IP_ALLOCATION_CHANGED", admin, null,
                Map.of("action", "DELETED", "ipAllocationId", ipAllocationId, "type", "STATIC"));

        String denied = "198.19.255.77";
        cleanUpDenyRuleValue(denied);
        Audited denyCreated = audited(admin, post("/api/v1/admin/ip-rules"),
                map("kind", "STATIC", "value", denied, "reason", "audit test"));
        long ipRuleId = body(denyCreated.result()).get("id").asLong();
        assertEvent(denyCreated, "IP_RULE_CHANGED", admin, null,
                Map.of("action", "CREATED", "ipRuleId", ipRuleId, "kind", "STATIC", "source", "MANUAL"));
        assertEvent(audited(admin, delete("/api/v1/admin/ip-rules/" + ipRuleId), null),
                "IP_RULE_CHANGED", admin, null,
                Map.of("action", "DELETED", "ipRuleId", ipRuleId, "kind", "STATIC", "source", "MANUAL"));

        JobLog log = jobLog();
        assertEvent(audited(admin, delete("/api/v1/admin/job-logs").param("ids", log.getId() + ",999999999"), null),
                "JOB_LOGS_DELETED", admin, null, Map.of("ids", List.of(log.getId())));
    }

    @Test
    void refusedNoOpAndSelfServiceRequestsWriteNoAdminEvent() throws Exception {
        Account admin = account(Role.ADMIN);
        Account user = account(Role.USER);
        Course course = course();
        IpAllocationRange ipAllocation = ipAllocation("203.0.113.0/30", 0xCB007100L, 0xCB007103L);

        // Refused by authorization.
        assertNoEvent(audited(user, put("/api/v1/admin/accounts/" + user.getId() + "/role"), map("role", "ADMIN")), 403);
        assertNoEvent(audited(user, delete("/api/v1/admin/ip-allocations/" + ipAllocation.getId()), null), 403);
        // Refused by a business guard.
        assertNoEvent(audited(admin, put("/api/v1/admin/accounts/" + admin.getId() + "/role"), map("role", "USER")),
                409);
        // No-ops.
        assertNoEvent(audited(admin, put("/api/v1/admin/accounts/" + user.getId() + "/role"), map("role", "USER")),
                200);
        assertNoEvent(audited(admin, delete("/api/v1/admin/accounts/" + user.getId() + "/enrollments/"
                + course.getId()), null), 204);
        // A USER managing their own enrollments is not an admin mutation.
        assertNoEvent(audited(user, post("/api/v1/me/enrollments"), map("courseId", course.getId())), 201);
        assertNoEvent(audited(user, delete("/api/v1/me/enrollments/" + course.getId()), null), 204);
    }

    @Test
    void adminRoutesAuditEnrollmentChangesEvenOnTheAdminsOwnAccount() throws Exception {
        Account admin = account(Role.ADMIN);
        Course viaAdminRoute = course();
        Course viaMeRoute = course();

        assertEvent(audited(admin, post("/api/v1/admin/accounts/" + admin.getId() + "/enrollments"),
                map("courseId", viaAdminRoute.getId())), "ENROLLMENT_CHANGED", admin, admin.getId(),
                Map.of("action", "ENROLLED", "courseId", viaAdminRoute.getId()));
        assertEvent(audited(admin, delete("/api/v1/admin/accounts/" + admin.getId() + "/enrollments/"
                + viaAdminRoute.getId()), null), "ENROLLMENT_CHANGED", admin, admin.getId(),
                Map.of("action", "DROPPED", "courseId", viaAdminRoute.getId()));

        // The same ADMIN using the self-service routes is not performing an admin mutation.
        assertNoEvent(audited(admin, post("/api/v1/me/enrollments"), map("courseId", viaMeRoute.getId())), 201);
        assertNoEvent(audited(admin, delete("/api/v1/me/enrollments/" + viaMeRoute.getId()), null), 204);
    }

    /**
     * Audit and change are atomic: a failing audit insert (injected by a trigger matching the request id) rolls
     * the change back, so neither the change nor an event is committed.
     */
    @Test
    void auditWriteFailureRollsBackTheChange() throws Exception {
        Account admin = account(Role.ADMIN);
        Account student = account(Role.USER);
        Course course = course();
        jdbc.execute("CREATE OR REPLACE FUNCTION authz_it_fail_audit() RETURNS trigger AS $fn$ "
                + "BEGIN IF NEW.request_id LIKE 'fail-audit-%' THEN RAISE EXCEPTION 'injected audit failure'; "
                + "END IF; RETURN NEW; END $fn$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER authz_it_fail_audit BEFORE INSERT ON security_event "
                + "FOR EACH ROW EXECUTE FUNCTION authz_it_fail_audit()");
        try {
            assertFailedWithoutEvent(admin, put("/api/v1/admin/accounts/" + student.getId() + "/role"),
                    map("role", "ADMIN"));
            assertFailedWithoutEvent(admin, delete("/api/v1/admin/accounts/" + student.getId()), null);
            assertFailedWithoutEvent(admin, put("/api/v1/admin/courses/" + course.getId()),
                    map("name", "authz-renamed-" + UUID.randomUUID(), "term", "2099/9"));
            assertFailedWithoutEvent(admin, post("/api/v1/admin/accounts/" + student.getId() + "/enrollments"),
                    map("courseId", course.getId()));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS authz_it_fail_audit ON security_event");
            jdbc.execute("DROP FUNCTION IF EXISTS authz_it_fail_audit()");
        }

        Account stored = accountRepository.findById(student.getId()).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(Role.USER);
        assertThat(stored.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(courseRepository.findById(course.getId()).orElseThrow().getName()).isEqualTo(course.getName());
        assertThat(enrollmentRepository.existsByAccountIdAndCourseId(student.getId(), course.getId())).isFalse();
        // Without the injected failure the same change commits together with its event.
        assertEvent(audited(admin, put("/api/v1/admin/accounts/" + student.getId() + "/role"), map("role", "ADMIN")),
                "ROLE_CHANGED", admin, student.getId(), Map.of("from", "USER", "to", "ADMIN"));
    }

    private void assertFailedWithoutEvent(Account caller, MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        String requestId = "fail-audit-" + UUID.randomUUID();
        int status;
        try {
            status = mockMvc.perform(as(caller, request, body).header("X-Request-Id", requestId))
                    .andReturn().getResponse().getStatus();
        } catch (jakarta.servlet.ServletException e) {
            // MockMvc rethrows unhandled exceptions; a real server answers 500.
            status = 500;
        }
        assertThat(status).as("request %s", requestId).isEqualTo(500);
        Long events = jdbc.queryForObject("SELECT count(*) FROM security_event WHERE request_id = ?", Long.class,
                requestId);
        assertThat(events).isZero();
    }

    @Test
    void detailsNeverContainPersonalData() throws Exception {
        Account admin = account(Role.ADMIN);
        Account student = account(Role.USER);
        IpAllocationRange range = ipAllocation("192.0.2.0/24", 0xC0000200L, 0xC00002FFL);

        Audited updated = audited(admin, put("/api/v1/admin/accounts/" + student.getId()),
                map("firstName", "Sensitive-First", "lastName", "Sensitive-Last", "studentNumber", "9711112222",
                        "ipAddress", "192.0.2.15"));

        assertThat(updated.result().getResponse().getStatus()).isEqualTo(200);
        Event event = eventOf(updated.requestId());
        assertThat(event.details().toString()).doesNotContain("Sensitive").doesNotContain("9711112222")
                .doesNotContain("192.0.2.15").doesNotContain(student.getUsername());
        assertThat(range.getId()).isPositive();
        // The response listing the event to an ADMIN carries the same row.
        String listing = perform(admin, get("/api/v1/admin/security-events").param("size", "100"), null)
                .getResponse().getContentAsString();
        assertThat(listing).contains(updated.requestId());
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    record Audited(MvcResult result, String requestId, String ip) {
    }

    private Audited audited(Account caller, MockHttpServletRequestBuilder request, Object body) throws Exception {
        String requestId = "audit-" + UUID.randomUUID();
        String ip = newIp();
        MockHttpServletRequestBuilder builder = as(caller, request, body).header("X-Request-Id", requestId)
                .with(from(ip));
        return new Audited(mockMvc.perform(builder).andReturn(), requestId, ip);
    }

    private void assertEvent(Audited audited, String type, Account actor, Long target, Map<String, Object> details)
            throws Exception {
        assertThat(audited.result().getResponse().getStatus()).as(audited.result().getResponse()
                .getContentAsString()).isBetween(200, 299);
        Event event = eventOf(audited.requestId());
        assertThat(event.type()).isEqualTo(type);
        assertThat(event.actor()).isEqualTo(actor.getId());
        assertThat(event.target()).isEqualTo(target);
        assertThat(event.ip()).isEqualTo(audited.ip());
        // Round-trip through JSON text so numbers compare by value, not by Java type.
        assertThat(event.details()).isEqualTo(json.readTree(json.writeValueAsString(details)));
    }

    private void assertNoEvent(Audited audited, int expectedStatus) {
        assertThat(audited.result().getResponse().getStatus()).isEqualTo(expectedStatus);
        Long count = jdbc.queryForObject("SELECT count(*) FROM security_event WHERE request_id = ?", Long.class,
                audited.requestId());
        assertThat(count).as("events for %s", audited.requestId()).isZero();
    }

    private Event eventOf(String requestId) throws Exception {
        List<Event> events = jdbc.query(
                "SELECT type, actor_account_id, target_account_id, ip, details::text AS details "
                        + "FROM security_event WHERE request_id = ?",
                (rs, row) -> {
                    try {
                        return new Event(rs.getString("type"), (Long) rs.getObject("actor_account_id"),
                                (Long) rs.getObject("target_account_id"), rs.getString("ip"),
                                json.readTree(rs.getString("details")));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                }, requestId);
        assertThat(events).as("events for request %s", requestId).hasSize(1);
        return events.get(0);
    }
}
