package com.educore.authz;

import com.educore.WeatherClient;
import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.entity.Enrollment;
import com.educore.entity.IpBlock;
import com.educore.entity.JobLog;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.repository.CourseRepository;
import com.educore.repository.EnrollmentRepository;
import com.educore.repository.IpBlockRepository;
import com.educore.repository.JobLogRepository;
import com.educore.security.AccessTokenAuthentication;
import com.educore.security.AuthenticatedUser;
import com.educore.security.JwtService;
import com.educore.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixtures for the authorization integration tests. Every test creates its own accounts, courses, IP
 * rules and job logs and removes them afterwards, so the shared seed data and other test classes are not
 * affected. All classes extending this one share one Spring context (same {@link MockitoBean} set); the
 * external weather API is replaced by a mock so no test reaches the network.
 */
@AutoConfigureMockMvc
public abstract class AuthzIntegrationSupport extends AbstractIntegrationTest {

    /** TEST DATA ONLY: password of the accounts created by these tests. */
    protected static final String PASSWORD = "authz-integration-test-only-value";

    /** Allowed by educore.cors.allowed-origins in application-test.yml. */
    protected static final String ALLOWED_ORIGIN = "http://localhost:3000";

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);
    private static volatile String passwordHash;

    @MockitoBean
    protected WeatherClient weatherClient;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    protected CourseRepository courseRepository;

    @Autowired
    protected EnrollmentRepository enrollmentRepository;

    @Autowired
    protected IpBlockRepository ipBlockRepository;

    @Autowired
    protected JobLogRepository jobLogRepository;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JdbcTemplate jdbc;

    protected final ObjectMapper json = new ObjectMapper();

    private final List<Long> accountIds = new ArrayList<>();
    private final List<String> studentNumbers = new ArrayList<>();
    private final List<Long> courseIds = new ArrayList<>();
    private final List<String> courseNames = new ArrayList<>();
    private final List<Long> ipRuleIds = new ArrayList<>();
    private final List<String> ipRuleValues = new ArrayList<>();
    private final List<Long> jobLogIds = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        SecurityContextHolder.clearContext();
        studentNumbers.forEach(number -> accountIds.addAll(
                jdbc.queryForList("SELECT id FROM account WHERE student_number = ?", Long.class, number)));
        courseNames.forEach(name -> courseIds.addAll(
                jdbc.queryForList("SELECT id FROM course WHERE name = ?", Long.class, name)));
        ipRuleValues.forEach(value -> ipRuleIds.addAll(
                jdbc.queryForList("SELECT id FROM ip_block WHERE original_value = ?", Long.class, value)));
        accountIds.forEach(id -> jdbc.update("DELETE FROM enrollments WHERE account_id = ?", id));
        courseIds.forEach(id -> jdbc.update("DELETE FROM enrollments WHERE course_id = ?", id));
        courseIds.forEach(id -> jdbc.update("DELETE FROM course WHERE id = ?", id));
        ipRuleIds.forEach(id -> jdbc.update("DELETE FROM ip_block WHERE id = ?", id));
        jobLogIds.forEach(id -> jdbc.update("DELETE FROM job_log WHERE id = ?", id));
        accountIds.forEach(id -> jdbc.update("DELETE FROM account WHERE id = ?", id));
        List.of(accountIds, studentNumbers, courseIds, courseNames, ipRuleIds, ipRuleValues, jobLogIds)
                .forEach(List::clear);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------

    protected Account account(Role role) {
        Account account = accountRepository.save(Account.builder()
                .username("authz-" + UUID.randomUUID())
                .password(passwordHash())
                .firstName("Fixture")
                .lastName(role == Role.ADMIN ? "Admin" : "User")
                .studentNumber(uniqueStudentNumber())
                .role(role)
                .deleted(0)
                .build());
        accountIds.add(account.getId());
        return account;
    }

    protected Course course() {
        Course course = courseRepository.save(Course.builder()
                .name("authz-course-" + UUID.randomUUID())
                .term("2026/1")
                .instructor("Instructor Fixture")
                .build());
        courseIds.add(course.getId());
        return course;
    }

    protected void enroll(Account account, Course course) {
        enrollmentRepository.save(Enrollment.builder()
                .account(accountRepository.getReferenceById(account.getId()))
                .course(courseRepository.getReferenceById(course.getId()))
                .build());
    }

    protected IpBlock ipRule(String cidr, long start, long end) {
        IpBlock block = ipBlockRepository.save(IpBlock.builder()
                .type("CIDR").originalValue(cidr).startIp(start).endIp(end).build());
        ipRuleIds.add(block.getId());
        return block;
    }

    protected JobLog jobLog() {
        JobLog log = jobLogRepository.save(JobLog.builder()
                .fileName("authz-fixture.csv").status("SUCCESS").entityType("STUDENTS")
                .createdAt(LocalDateTime.now()).detailedLogs("fixture").build());
        jobLogIds.add(log.getId());
        return log;
    }

    /** Registers an account created through the API (by its student number) for removal. */
    protected void cleanUpStudentNumber(String studentNumber) {
        studentNumbers.add(studentNumber);
    }

    protected void cleanUpAccount(long accountId) {
        accountIds.add(accountId);
    }

    /** Registers a course created through the API (by its unique name) for removal. */
    protected void cleanUpCourseName(String name) {
        courseNames.add(name);
    }

    /** Registers an IP rule created through the API (by its value) for removal. */
    protected void cleanUpIpRuleValue(String value) {
        ipRuleValues.add(value);
    }

    protected void cleanUpJobLog(long id) {
        jobLogIds.add(id);
    }

    // ---- requests ---------------------------------------------------------------------------------------

    protected String bearer(Account account) {
        return "Bearer " + jwtService.issue(AuthenticatedUser.of(account)).token();
    }

    /** Sets the socket peer address of the request (every request in these tests uses a fresh one). */
    protected static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    protected static String newIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "10.240." + ((n >> 8) & 0xff) + "." + (n & 0xff);
    }

    protected static String uniqueStudentNumber() {
        return "97" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
    }

    /** Adds the bearer token of {@code caller} (if any), a fresh client IP and a JSON body (if any). */
    protected MockHttpServletRequestBuilder as(Account caller, MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        request.with(from(newIp()));
        if (caller != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(caller));
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return request;
    }

    protected MvcResult perform(Account caller, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(as(caller, request, body)).andReturn();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Runs service calls as {@code user}, the way {@code JwtAuthenticationFilter} would authenticate them. */
    protected static void authenticateAs(AuthenticatedUser user) {
        SecurityContextHolder.getContext().setAuthentication(new AccessTokenAuthentication(user));
    }

    protected static Map<String, Object> map(Object... keyValues) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private String passwordHash() {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASSWORD);
        }
        return passwordHash;
    }
}
