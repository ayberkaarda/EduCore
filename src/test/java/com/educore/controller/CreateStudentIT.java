package com.educore.controller;

import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.repository.AccountRepository;
import com.educore.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** {@code POST /api/v1/admin/accounts/students}: random temporary password returned once to the ADMIN caller. */
@AutoConfigureMockMvc
class CreateStudentIT extends AbstractIntegrationTest {

    /** TEST DATA ONLY: password of the caller accounts created by this test. */
    private static final String CALLER_PASSWORD = "create-student-it-only-value";

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> createdAccountIds = new ArrayList<>();

    @AfterEach
    void deleteCreatedAccounts() {
        createdAccountIds.forEach(accountRepository::deleteById);
        createdAccountIds.clear();
    }

    @Test
    void adminReceivesTheTemporaryPasswordOnceAndTheStudentMustChangeIt() throws Exception {
        String adminToken = accessToken(caller(Role.ADMIN));
        String studentNumber = uniqueStudentNumber();

        MvcResult created = createStudent(adminToken, Map.of(
                "firstName", "Temp", "lastName", "Student", "studentNumber", studentNumber));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String responseText = created.getResponse().getContentAsString();
        JsonNode body = json.readTree(responseText);
        long id = body.get("id").asLong();
        createdAccountIds.add(id);
        String temporaryPassword = body.get("temporaryPassword").asText();
        assertThat(temporaryPassword).hasSize(24);
        assertThat(body.get("studentNumber").asText()).isEqualTo(studentNumber);
        assertThat(body.get("role").asText()).isEqualTo("USER");
        // Internal flags never leave the server; mustChangePassword is reported by the login response below.
        assertThat(body.has("deleted")).isFalse();
        assertThat(body.has("mustChangePassword")).isFalse();
        assertThat(body.has("password")).isFalse();
        assertThat(body.has("authorities")).isFalse();

        Account stored = accountRepository.findById(id).orElseThrow();
        assertThat(stored.isMustChangePassword()).isTrue();
        assertThat(stored.getPassword()).startsWith("{bcrypt}$2a$12$").isNotEqualTo(temporaryPassword);
        assertThat(responseText).doesNotContain(stored.getPassword()).doesNotContain("$2a$");

        // Returned once: the student listing never carries it.
        String listing = mockMvc.perform(get("/api/v1/admin/accounts/students").param("search", studentNumber)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).contains(studentNumber).doesNotContain(temporaryPassword)
                .doesNotContain("temporaryPassword");

        MvcResult studentLogin = login(body.get("username").asText(), temporaryPassword);
        assertThat(studentLogin.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(studentLogin.getResponse().getContentAsString())
                .get("user").get("mustChangePassword").asBoolean()).isTrue();
    }

    @Test
    void clientSuppliedPasswordAndIdAreIgnored() throws Exception {
        String adminToken = accessToken(caller(Role.ADMIN));
        Account victim = caller(Role.USER);
        String victimHash = victim.getPassword();
        /* TEST DATA ONLY */
        String supplied = "client-supplied-it-only-value";

        MvcResult created = createStudent(adminToken, Map.of("id", victim.getId(), "firstName", "Other",
                "lastName", "Student", "studentNumber", uniqueStudentNumber(), "password", supplied));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json.readTree(created.getResponse().getContentAsString());
        long id = body.get("id").asLong();
        createdAccountIds.add(id);
        assertThat(id).isNotEqualTo(victim.getId());
        assertThat(accountRepository.findById(victim.getId()).orElseThrow().getPassword()).isEqualTo(victimHash);
        Account stored = accountRepository.findById(id).orElseThrow();
        assertThat(passwordEncoder.matches(supplied, stored.getPassword())).isFalse();
        assertThat(passwordEncoder.matches(body.get("temporaryPassword").asText(), stored.getPassword())).isTrue();
    }

    @Test
    void nonAdminCannotCreateStudents() throws Exception {
        String userToken = accessToken(caller(Role.USER));
        String studentNumber = uniqueStudentNumber();

        MvcResult result = createStudent(userToken, Map.of(
                "firstName", "Denied", "lastName", "Student", "studentNumber", studentNumber));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("temporaryPassword");
        assertThat(accountRepository.findByStudentNumber(studentNumber)).isEmpty();
    }

    private MvcResult createStudent(String accessToken, Map<String, Object> request) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/accounts/students")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andReturn();
    }

    private Account caller(Role role) {
        Account account = accountRepository.save(Account.builder()
                .username("it-" + UUID.randomUUID())
                .password(passwordEncoder.encode(CALLER_PASSWORD))
                .firstName("Caller")
                .lastName("Account")
                .role(role)
                .build());
        createdAccountIds.add(account.getId());
        return account;
    }

    private String accessToken(Account account) throws Exception {
        MvcResult result = login(account.getUsername(), CALLER_PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private MvcResult login(String username, String password) throws Exception {
        int n = IP_SEQUENCE.getAndIncrement();
        String ip = "10.250." + ((n >> 8) & 0xff) + "." + (n & 0xff);
        return mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn();
    }

    /** Synthetic student numbers in the obviously fake 98xxxxxx range. */
    private static String uniqueStudentNumber() {
        return "98" + ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
    }
}
