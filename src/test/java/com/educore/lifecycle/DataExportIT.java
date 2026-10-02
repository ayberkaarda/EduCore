package com.educore.lifecycle;

import com.educore.auth.UsernameHasher;
import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code GET /api/v1/me/export}: content (profile, enrollments, own security events), no secrets or internal
 * flags, download headers, 1 export per account and minute, and authentication.
 */
class DataExportIT extends LifecycleIntegrationSupport {

    @Autowired
    private UsernameHasher usernameHasher;

    @Test
    void exportContainsProfileEnrollmentsAndOwnSecurityEvents() throws Exception {
        Account admin = account(Role.ADMIN);
        Account user = account(Role.USER);
        Course own = course();
        enroll(user, own);
        String loginIp = newIp();
        assertThat(mockMvc.perform(post("/api/v1/auth/login").with(from(loginIp))
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", user.getUsername(), "password", PASSWORD))))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        String adminIp = newIp();
        Course byAdmin = course();
        assertThat(mockMvc.perform(post("/api/v1/admin/accounts/" + user.getId() + "/enrollments").with(from(adminIp))
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("courseId", byAdmin.getId()))))
                .andReturn().getResponse().getStatus()).isEqualTo(201);

        MvcResult result = perform(user, get("/api/v1/me/export"), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .matches("attachment; filename=\"educore-account-export-\\d{8}\\.json\"");
        JsonNode export = body(result);
        assertThat(export.fieldNames()).toIterable().containsExactlyInAnyOrder("format", "exportedAt", "profile",
                "enrollments", "securityEvents", "securityEventsTruncated");
        assertThat(export.get("format").asText()).isEqualTo("educore.account-export.v1");
        assertThat(export.get("securityEventsTruncated").asBoolean()).isFalse();

        JsonNode profile = export.get("profile");
        assertThat(profile.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "username", "firstName",
                "lastName", "studentNumber", "role", "ipAddress", "status");
        assertThat(profile.get("id").asLong()).isEqualTo(user.getId());
        assertThat(profile.get("username").asText()).isEqualTo(user.getUsername());
        assertThat(profile.get("studentNumber").asText()).isEqualTo(user.getStudentNumber());
        assertThat(profile.get("status").asText()).isEqualTo("ACTIVE");

        List<Long> courseIds = new ArrayList<>();
        export.get("enrollments").forEach(enrollment -> {
            assertThat(enrollment.fieldNames()).toIterable().containsExactlyInAnyOrder("courseId", "courseName",
                    "term", "instructor", "enrolledAt");
            courseIds.add(enrollment.get("courseId").asLong());
        });
        assertThat(courseIds).containsExactlyInAnyOrder(own.getId(), byAdmin.getId());

        Map<String, JsonNode> events = new java.util.HashMap<>();
        export.get("securityEvents").forEach(event -> {
            assertThat(event.fieldNames()).toIterable().containsExactlyInAnyOrder("type", "at", "involvement", "ip");
            events.put(event.get("type").asText(), event);
        });
        assertThat(events.get("AUTH_LOGIN_SUCCESS").get("involvement").asText()).isEqualTo("ACTOR_AND_TARGET");
        assertThat(events.get("AUTH_LOGIN_SUCCESS").get("ip").asText()).isEqualTo(loginIp);
        // An ADMIN's action on the account is listed, without the ADMIN's IP address.
        assertThat(events.get("ENROLLMENT_CHANGED").get("involvement").asText()).isEqualTo("TARGET");
        assertThat(events.get("ENROLLMENT_CHANGED").get("ip").isNull()).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(adminIp);

        assertThat(count("SELECT count(*) FROM security_event WHERE type = 'DATA_EXPORTED' AND actor_account_id = ?",
                user.getId())).isEqualTo(1);
    }

    @Test
    void exportContainsNoSecretsHashesOrInternalFlags() throws Exception {
        Account user = account(Role.USER);
        MvcResult login = login(user, PASSWORD);
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(login(user, "wrong-password-for-the-export").getResponse().getStatus()).isEqualTo(401);
        String passwordHash = jdbc.queryForObject("SELECT password FROM account WHERE id = ?", String.class,
                user.getId());
        String tokenHash = jdbc.queryForObject("SELECT token_hash FROM refresh_token WHERE account_id = ? LIMIT 1",
                String.class, user.getId());
        String familyId = jdbc.queryForObject("SELECT id::text FROM refresh_token_family WHERE account_id = ? LIMIT 1",
                String.class, user.getId());
        List<String> requestIds = jdbc.queryForList("SELECT request_id FROM security_event WHERE target_account_id = ? "
                + "AND request_id IS NOT NULL", String.class, user.getId());

        String body = perform(user, get("/api/v1/me/export"), null).getResponse().getContentAsString();

        assertThat(body).doesNotContain(passwordHash).doesNotContain("$2a$").doesNotContain("{bcrypt}")
                .doesNotContain(tokenHash).doesNotContain(refreshCookie(login)).doesNotContain(familyId)
                .doesNotContain(usernameHasher.hash(user.getUsername()))
                .doesNotContain("password").doesNotContain("usernameHash").doesNotContain("mustChangePassword")
                .doesNotContain("version").doesNotContain("requestId").doesNotContain("deletedAt")
                .doesNotContain("token").doesNotContain("details");
        requestIds.forEach(requestId -> assertThat(body).doesNotContain(requestId));
    }

    @Test
    void oneExportPerAccountAndMinute() throws Exception {
        Account user = account(Role.USER);
        Account other = account(Role.USER);

        assertThat(perform(user, get("/api/v1/me/export"), null).getResponse().getStatus()).isEqualTo(200);
        MvcResult limited = perform(user, get("/api/v1/me/export"), null);

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(body(limited).get("code").asText()).isEqualTo("rate-limit/exceeded");
        long retryAfter = Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 60L);
        assertThat(limited.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        // The bucket is per account.
        assertThat(perform(other, get("/api/v1/me/export"), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM security_event WHERE type = 'DATA_EXPORTED' AND actor_account_id = ?",
                user.getId())).isEqualTo(1);
    }

    @Test
    void exportRequiresAnActiveSignedInAccount() throws Exception {
        MvcResult anonymous = perform(null, get("/api/v1/me/export"), null);
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(anonymous).get("code").asText()).isEqualTo("auth/unauthenticated");

        Account pending = account(Role.USER);
        assertThat(requestDeletion(pending, PASSWORD).getResponse().getStatus()).isEqualTo(202);
        MvcResult denied = perform(pending, get("/api/v1/me/export"), null);
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(denied).get("code").asText()).isEqualTo("account/pending-deletion");

        Account deactivated = account(Role.USER);
        jdbc.update("UPDATE account SET status = 'DEACTIVATED' WHERE id = ?", deactivated.getId());
        assertThat(perform(deactivated, get("/api/v1/me/export"), null).getResponse().getStatus()).isEqualTo(401);
    }
}
