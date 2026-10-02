package com.educore.authz;

import com.educore.entity.Account;
import com.educore.entity.Role;
import com.educore.service.AccountCredentialService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * R-03 / AC-04: {@code mustChangePassword} is enforced by the server. A temporary password (API-created and
 * CSV-imported students) or the bootstrap password is not a full credential: its session may only read the signed-in
 * user, change the password, refresh and log out; everything else is 403 {@code account/password-change-required}.
 * The bootstrap ADMIN is covered end to end in {@code AdminBootstrapIT}, the management port in
 * {@code ManagementEndpointSecurityIT}.
 */
class PasswordChangeRequiredScopeIT extends AuthzIntegrationSupport {

    private static final String CODE = "account/password-change-required";
    /** TEST DATA ONLY: a policy-compliant password chosen by the account owner. */
    private static final String OWN_PASSWORD = "scope-test-own-value-2026";

    @Autowired
    private AccountCredentialService credentials;

    private MvcResult call(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.with(from(newIp())).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").with(from(newIp())).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password)))).andReturn();
    }

    /** Every route outside the scope answers 403 with the scope code, including variants and unknown paths. */
    private void assertRestricted(String token, Account account) throws Exception {
        List<MvcResult> denied = List.of(
                call(token, get("/api/v1/me"), null),
                call(token, put("/api/v1/me"), Map.of("firstName", "New", "lastName", "Name")),
                call(token, get("/api/v1/me/enrollments"), null),
                call(token, get("/api/v1/me/export"), null),
                call(token, delete("/api/v1/me"), Map.of("currentPassword", PASSWORD)),
                call(token, get("/api/v1/courses"), null),
                call(token, get("/api/v1/weather"), null),
                call(token, get("/api/v1/admin/accounts"), null),
                call(token, get("/api/v1/auth/me/"), null),
                call(token, get("/api/v1/unknown-path"), null));
        for (MvcResult result : denied) {
            assertThat(result.getResponse().getStatus()).as(result.getRequest().getRequestURI()).isEqualTo(403);
            assertThat(body(result).get("code").asText()).as(result.getRequest().getRequestURI()).isEqualTo(CODE);
        }
        MvcResult me = call(token, get("/api/v1/auth/me"), null);
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(me).get("id").asLong()).isEqualTo(account.getId());
        assertThat(body(me).get("mustChangePassword").asBoolean()).isTrue();
    }

    /** API-created student: the temporary password from the ADMIN signs in, but only into the restricted scope. */
    @Test
    void anApiCreatedStudentMustChangeTheTemporaryPasswordFirst() throws Exception {
        Account admin = account(Role.ADMIN);
        String studentNumber = uniqueStudentNumber();
        cleanUpStudentNumber(studentNumber);
        MvcResult created = perform(admin, post("/api/v1/admin/accounts/students"),
                Map.of("firstName", "Scope", "lastName", "Student", "studentNumber", studentNumber));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode student = body(created);
        String username = student.get("username").asText();
        String temporaryPassword = student.get("temporaryPassword").asText();

        MvcResult session = login(username, temporaryPassword);
        assertThat(session.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(session).get("user").get("mustChangePassword").asBoolean()).isTrue();
        String token = body(session).get("accessToken").asText();
        Account account = accountRepository.findByUsername(username).orElseThrow();
        assertRestricted(token, account);

        MvcResult changed = call(token, post("/api/v1/auth/password"),
                Map.of("currentPassword", temporaryPassword, "newPassword", OWN_PASSWORD));
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(changed).get("user").get("mustChangePassword").asBoolean()).isFalse();
        String full = body(changed).get("accessToken").asText();
        assertThat(call(full, get("/api/v1/me"), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(full, get("/api/v1/me/enrollments"), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(token, get("/api/v1/me"), null).getResponse().getStatus())
                .as("the role comes from the database on every request: the old token is no longer restricted")
                .isEqualTo(200);
    }

    /** The CSV import assigns credentials through the same service: same restricted scope. */
    @Test
    void anImportedStudentIsRestrictedUntilThePasswordIsChanged() throws Exception {
        Account account = account(Role.USER);
        Account stored = accountRepository.findById(account.getId()).orElseThrow();
        String temporaryPassword = credentials.assignTemporaryPassword(stored);
        accountRepository.saveAndFlush(stored);

        MvcResult session = login(account.getUsername(), temporaryPassword);
        assertThat(session.getResponse().getStatus()).isEqualTo(200);
        assertRestricted(body(session).get("accessToken").asText(), account);
        // The refresh path keeps the scope: a refreshed token is restricted as well.
        String cookie = session.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("educore_rt=")).findFirst().orElseThrow();
        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh").with(from(newIp()))
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(new jakarta.servlet.http.Cookie("educore_rt",
                        cookie.substring("educore_rt=".length(), cookie.indexOf(';'))))).andReturn();
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        assertRestricted(body(refreshed).get("accessToken").asText(), account);
    }

    /** An ADMIN with the flag (the bootstrap ADMIN until its first change) has no role at all in this scope. */
    @Test
    void anAdminWithATemporaryPasswordReachesNoAdminRoute() throws Exception {
        Account admin = account(Role.ADMIN);
        jdbc.update("UPDATE account SET must_change_password = true WHERE id = ?", admin.getId());
        String token = bearer(admin).substring("Bearer ".length());

        assertRestricted(token, admin);
        for (MockHttpServletRequestBuilder request : List.of(get("/api/v1/admin/security-events"),
                post("/api/v1/admin/accounts/" + admin.getId() + "/unlock-login"),
                delete("/api/v1/admin/accounts/" + UUID.randomUUID().getMostSignificantBits()))) {
            MvcResult result = call(token, request, null);
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(result).get("code").asText()).isEqualTo(CODE);
        }
        MvcResult logout = mockMvc.perform(post("/api/v1/auth/logout").with(from(newIp()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)).andReturn();
        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
    }
}
