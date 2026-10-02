package com.educore.lifecycle;

import com.educore.auth.UsernameHasher;
import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Account lifecycle (P7, S21): deletion request with re-authentication, the restore-only grace scope, restore,
 * the purge after the grace period (cascades, pseudonymised audit trail, webhook), its safety under concurrent
 * runs, and the ADMIN soft/hard delete and restore routes with their guards.
 */
class AccountLifecycleIT extends LifecycleIntegrationSupport {

    private static final Duration AFTER_GRACE = Duration.ofDays(31);
    private static final String WEBHOOK_URL = "https://lifecycle-hook.example.com/";

    @Autowired
    private AccountPurgeJob purgeJob;

    @Autowired
    private Pseudonyms pseudonyms;

    @Autowired
    private UsernameHasher usernameHasher;

    @AfterEach
    void removeWebhooks() {
        jdbc.update("DELETE FROM webhook_subscription WHERE url LIKE ?", WEBHOOK_URL + "%");
    }

    // ---- deletion request -------------------------------------------------------------------------------

    @Test
    void deletionRequiresTheCorrectCurrentPassword() throws Exception {
        Account user = account(Role.USER);
        String usernameHash = usernameHasher.hash(user.getUsername());
        long failuresBefore = count("SELECT count(*) FROM login_attempt WHERE username_hash = ? AND NOT success",
                usernameHash);

        MvcResult wrong = requestDeletion(user, "not-the-current-password");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(wrong).get("code").asText()).isEqualTo("auth/invalid-current-password");
        assertThat(status(user)).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM login_attempt WHERE username_hash = ? AND NOT success", usernameHash))
                .isEqualTo(failuresBefore + 1);

        MvcResult missing = perform(user, delete("/api/v1/me"), Map.of());
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(missing).get("code").asText()).isEqualTo("request/invalid");
        MvcResult noBody = perform(user, delete("/api/v1/me"), null);
        assertThat(noBody.getResponse().getStatus()).isEqualTo(400);
        assertThat(status(user)).isEqualTo("ACTIVE");

        Instant before = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        MvcResult accepted = requestDeletion(user, PASSWORD);
        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        assertThat(accepted.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(accepted.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(cookie -> cookie.startsWith("educore_rt=;") && cookie.contains("Max-Age=0"));
        JsonNode scheduled = body(accepted);
        assertThat(scheduled.get("status").asText()).isEqualTo("PENDING_DELETION");
        Instant deleteAfter = Instant.parse(scheduled.get("deleteAfter").asText());
        assertThat(deleteAfter).isBetween(before.plus(Duration.ofDays(30)),
                clock.instant().plus(Duration.ofDays(30)));
        assertThat(status(user)).isEqualTo("PENDING_DELETION");
        assertThat(count("SELECT count(*) FROM security_event WHERE type = 'ACCOUNT_DELETION_REQUESTED' "
                + "AND actor_account_id = ? AND target_account_id = ?", user.getId(), user.getId())).isEqualTo(1);
    }

    @Test
    void deletionRevokesEveryRefreshTokenFamily() throws Exception {
        Account user = account(Role.USER);
        String first = refreshCookie(login(user, PASSWORD));
        String second = refreshCookie(login(user, PASSWORD));
        assertThat(count("SELECT count(*) FROM refresh_token_family WHERE account_id = ? AND revoked_at IS NULL",
                user.getId())).isEqualTo(2);

        assertThat(requestDeletion(user, PASSWORD).getResponse().getStatus()).isEqualTo(202);

        assertThat(count("SELECT count(*) FROM refresh_token_family WHERE account_id = ? AND revoked_at IS NULL",
                user.getId())).isZero();
        assertThat(count("SELECT count(*) FROM refresh_token WHERE account_id = ? AND revoked_at IS NULL",
                user.getId())).isZero();
        for (String cookie : List.of(first, second)) {
            MvcResult refused = refresh(cookie);
            assertThat(refused.getResponse().getStatus()).isEqualTo(401);
            assertThat(body(refused).get("code").asText()).isEqualTo("auth/invalid-refresh-token");
        }
    }

    // ---- grace period -----------------------------------------------------------------------------------

    @Test
    void duringTheGracePeriodOnlyProfileRestoreAndLogoutAreAllowed() throws Exception {
        Account user = account(Role.USER);
        Course course = course();
        enroll(user, course);
        assertThat(requestDeletion(user, PASSWORD).getResponse().getStatus()).isEqualTo(202);

        MvcResult profile = perform(user, get("/api/v1/me"), null);
        assertThat(profile.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(profile).get("status").asText()).isEqualTo("PENDING_DELETION");
        assertThat(body(profile).get("deleteAfter").isTextual()).isTrue();

        List<MvcResult> denied = List.of(
                perform(user, get("/api/v1/me/enrollments"), null),
                perform(user, put("/api/v1/me"), Map.of("firstName", "New", "lastName", "Name")),
                perform(user, delete("/api/v1/me/enrollments/" + course.getId()), null),
                perform(user, get("/api/v1/me/export"), null),
                perform(user, get("/api/v1/auth/me"), null),
                perform(user, post("/api/v1/auth/password"), Map.of("currentPassword", PASSWORD,
                        "newPassword", "Lifecycle-" + UUID.randomUUID())),
                perform(user, delete("/api/v1/me"), Map.of("currentPassword", PASSWORD)),
                perform(user, get("/api/v1/courses"), null),
                perform(user, get("/api/v1/me/"), null),
                perform(user, get("/api/v1/unknown-path"), null));
        for (MvcResult result : denied) {
            assertThat(result.getResponse().getStatus()).as(result.getRequest().getRequestURI()).isEqualTo(403);
            assertThat(body(result).get("code").asText()).isEqualTo("account/pending-deletion");
        }
        assertThat(enrollmentRepository.existsByAccountIdAndCourseId(user.getId(), course.getId())).isTrue();

        // Signing in again is possible during the grace period; the session has the same restricted scope.
        MvcResult login = login(user, PASSWORD);
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(login).get("user").get("status").asText()).isEqualTo("PENDING_DELETION");
        String cookie = refreshCookie(login);
        MvcResult refreshed = refresh(cookie);
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        String token = accessToken(refreshed);
        MvcResult viaSession = mockMvc.perform(get("/api/v1/me/enrollments").with(from(newIp()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        assertThat(viaSession.getResponse().getStatus()).isEqualTo(403);

        MvcResult logout = mockMvc.perform(post("/api/v1/auth/logout").with(from(newIp()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(new jakarta.servlet.http.Cookie("educore_rt", refreshCookie(refreshed)))).andReturn();
        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void anAdminPendingDeletionLosesEveryAdminRoute() throws Exception {
        Account admin = account(Role.ADMIN);
        assertThat(requestDeletion(admin, PASSWORD).getResponse().getStatus()).isEqualTo(202);

        MvcResult listing = perform(admin, get("/api/v1/admin/accounts"), null);
        assertThat(listing.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(listing).get("code").asText()).isEqualTo("account/pending-deletion");
        assertThat(perform(admin, get("/api/v1/me"), null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void restoreInsideTheGracePeriodReactivatesTheAccount() throws Exception {
        Account user = account(Role.USER);
        assertThat(requestDeletion(user, PASSWORD).getResponse().getStatus()).isEqualTo(202);
        clock.advance(Duration.ofDays(29));

        MvcResult restored = restore(user, PASSWORD);

        assertThat(restored.getResponse().getStatus()).isEqualTo(200);
        assertThat(restored.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(body(restored).get("user").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body(restored).get("accessToken").asText()).isNotBlank();
        assertThat(refreshCookie(restored)).isNotBlank();
        assertThat(status(user)).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM account WHERE id = ? AND deleted_at IS NULL AND delete_after IS NULL",
                user.getId())).isEqualTo(1);
        assertThat(perform(user, get("/api/v1/me/enrollments"), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM security_event WHERE type = 'ACCOUNT_RESTORED' AND target_account_id = ? "
                + "AND actor_account_id = ? AND details ->> 'from' = 'PENDING_DELETION'", user.getId(), user.getId()))
                .isEqualTo(1);
        // A second restore is a no-op for the account: same answer, no second event.
        assertThat(restore(user, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM security_event WHERE type = 'ACCOUNT_RESTORED' AND target_account_id = ?",
                user.getId())).isEqualTo(1);
        // Not purged later.
        clock.advance(AFTER_GRACE);
        purgeJob.purgeDue();
        assertThat(status(user)).isEqualTo("ACTIVE");
    }

    @Test
    void afterTheGracePeriodTheAccountCanNeitherSignInNorBeRestoredByItsOwner() throws Exception {
        Account user = account(Role.USER);
        assertThat(requestDeletion(user, PASSWORD).getResponse().getStatus()).isEqualTo(202);
        clock.advance(AFTER_GRACE);

        assertThat(perform(user, get("/api/v1/me"), null).getResponse().getStatus()).isEqualTo(401);
        assertThat(restore(user, PASSWORD).getResponse().getStatus()).isEqualTo(401);
        MvcResult login = login(user, PASSWORD);
        assertThat(login.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(login).get("code").asText()).isEqualTo("auth/invalid-credentials");
    }

    // ---- purge ------------------------------------------------------------------------------------------

    @Test
    void purgeAfterTheGracePeriodCascadesAndPseudonymisesTheAuditTrail() throws Exception {
        Account admin = account(Role.ADMIN);
        Account user = account(Role.USER);
        Course course = course();
        enroll(user, course);
        long webhookId = webhookSubscription();
        String usernameHash = usernameHasher.hash(user.getUsername());
        // Footprint: a failed and a successful login (attempts, events with the user's IP and username hash), an
        // ADMIN action on the account and the deletion request itself.
        assertThat(login(user, "wrong-password-for-the-test").getResponse().getStatus()).isEqualTo(401);
        assertThat(login(user, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(perform(admin, post("/api/v1/admin/accounts/" + user.getId() + "/enrollments"),
                Map.of("courseId", course().getId())).getResponse().getStatus()).isEqualTo(201);
        assertThat(requestDeletion(user, PASSWORD).getResponse().getStatus()).isEqualTo(202);
        long ownEvents = count("SELECT count(*) FROM security_event WHERE actor_account_id = ? OR target_account_id = ?",
                user.getId(), user.getId());
        assertThat(ownEvents).isGreaterThanOrEqualTo(4);
        assertThat(count("SELECT count(*) FROM login_attempt WHERE username_hash = ?", usernameHash)).isEqualTo(3);

        // Not due yet: nothing happens.
        purgeJob.purgeDue();
        assertThat(status(user)).isEqualTo("PENDING_DELETION");

        clock.advance(AFTER_GRACE);
        assertThat(purgeJob.purgeDue()).isGreaterThanOrEqualTo(1);

        String pseudonym = pseudonyms.ofAccount(user.getId());
        assertThat(pseudonym).matches("purged:[0-9a-f]{16}");
        assertThat(status(user)).isNull();
        assertThat(count("SELECT count(*) FROM enrollments WHERE account_id = ?", user.getId())).isZero();
        assertThat(count("SELECT count(*) FROM refresh_token WHERE account_id = ?", user.getId())).isZero();
        assertThat(count("SELECT count(*) FROM refresh_token_family WHERE account_id = ?", user.getId())).isZero();
        assertThat(count("SELECT count(*) FROM login_attempt WHERE username_hash = ?", usernameHash)).isZero();
        assertThat(count("SELECT count(*) FROM security_event WHERE actor_account_id = ? OR target_account_id = ?",
                user.getId(), user.getId())).isZero();
        assertThat(count("SELECT count(*) FROM security_event WHERE (actor_pseudonym = ? OR target_pseudonym = ?) "
                + "AND type <> 'ACCOUNT_PURGED'", pseudonym, pseudonym)).isEqualTo(ownEvents);
        assertThat(count("SELECT count(*) FROM security_event WHERE actor_pseudonym = ? AND ip IS NOT NULL",
                pseudonym)).isZero();
        assertThat(count("SELECT count(*) FROM security_event WHERE details ->> 'usernameHash' = ?", usernameHash))
                .isZero();
        // The ADMIN's action keeps the ADMIN as actor; only the purged side is pseudonymised.
        assertThat(count("SELECT count(*) FROM security_event WHERE type = 'ENROLLMENT_CHANGED' AND actor_account_id = ? "
                + "AND target_pseudonym = ?", admin.getId(), pseudonym)).isEqualTo(1);

        List<Map<String, Object>> purged = jdbc.queryForList("SELECT actor_account_id, target_account_id, "
                + "details ->> 'trigger' AS trigger, (details ->> 'enrollments')::int AS enrollments "
                + "FROM security_event WHERE type = 'ACCOUNT_PURGED' AND target_pseudonym = ?", pseudonym);
        assertThat(purged).hasSize(1);
        assertThat(purged.get(0).get("trigger")).isEqualTo("GRACE_EXPIRED");
        assertThat(purged.get(0).get("enrollments")).isEqualTo(2);
        assertThat(purged.get(0).get("actor_account_id")).isNull();
        assertThat(purged.get(0).get("target_account_id")).isNull();

        List<String> payloads = jdbc.queryForList("SELECT payload::text FROM webhook_delivery "
                + "WHERE subscription_id = ? AND event = 'account.deleted'", String.class, webhookId);
        assertThat(payloads).hasSize(1);
        JsonNode data = json.readTree(payloads.get(0)).get("data");
        assertThat(data.get("accountId").asLong()).isEqualTo(user.getId());
        assertThat(data.get("mode").asText()).isEqualTo("HARD");
        assertThat(payloads.get(0)).doesNotContain(user.getUsername()).doesNotContain(user.getStudentNumber());

        // Idempotent: a second run finds nothing of this account.
        purgeJob.purgeDue();
        assertThat(count("SELECT count(*) FROM security_event WHERE type = 'ACCOUNT_PURGED' AND target_pseudonym = ?",
                pseudonym)).isEqualTo(1);
    }

    @Test
    void concurrentPurgeRunsPurgeEveryDueAccountExactlyOnce() throws Exception {
        List<Account> users = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Account user = account(Role.USER);
            enroll(user, course());
            assertThat(requestDeletion(user, PASSWORD).getResponse().getStatus()).isEqualTo(202);
            users.add(user);
        }
        clock.advance(AFTER_GRACE);

        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> runs = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<Integer> run = () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return purgeJob.purgeDue();
                };
                runs.add(executor.submit(run));
            }
            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get(60, TimeUnit.SECONDS);
            }
            assertThat(total).isGreaterThanOrEqualTo(users.size());
        } finally {
            executor.shutdownNow();
        }
        for (Account user : users) {
            assertThat(status(user)).isNull();
            assertThat(count("SELECT count(*) FROM security_event WHERE type = 'ACCOUNT_PURGED' AND target_pseudonym = ?",
                    pseudonyms.ofAccount(user.getId()))).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM enrollments WHERE account_id = ?", user.getId())).isZero();
        }
    }

    // ---- guards -----------------------------------------------------------------------------------------

    @Test
    void theLastActiveAdminCannotRequestTheirOwnDeletion() throws Exception {
        Account admin = account(Role.ADMIN);
        List<Long> others = jdbc.queryForList("SELECT id FROM account WHERE role = 'ADMIN' AND status = 'ACTIVE' "
                + "AND id <> ?", Long.class, admin.getId());
        others.forEach(id -> jdbc.update("UPDATE account SET status = 'DEACTIVATED' WHERE id = ?", id));
        try {
            MvcResult refused = requestDeletion(admin, PASSWORD);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused).get("code").asText()).isEqualTo("account/last-admin");
            assertThat(status(admin)).isEqualTo("ACTIVE");
        } finally {
            others.forEach(id -> jdbc.update("UPDATE account SET status = 'ACTIVE' WHERE id = ?", id));
        }
        // With another active ADMIN the request is accepted.
        assertThat(requestDeletion(admin, PASSWORD).getResponse().getStatus()).isEqualTo(202);
    }

    /**
     * R-22: the hard delete is {@code POST /admin/accounts/{id}/purge} with {@code {confirm}} in the JSON body, so the
     * username never appears in a request line; the former {@code DELETE ?mode=hard&confirm=} form is refused.
     */
    @Test
    void adminPurgeNeedsTheUsernameInTheBodyAndKeepsTheSelfGuard() throws Exception {
        Account admin = account(Role.ADMIN);
        Account user = account(Role.USER);
        enroll(user, course());

        MvcResult unconfirmed = perform(admin, post("/api/v1/admin/accounts/" + user.getId() + "/purge"), Map.of());
        assertThat(unconfirmed.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(unconfirmed).get("code").asText()).isEqualTo("request/invalid");
        MvcResult noBody = perform(admin, post("/api/v1/admin/accounts/" + user.getId() + "/purge"), null);
        assertThat(noBody.getResponse().getStatus()).isEqualTo(400);
        MvcResult wrong = perform(admin, post("/api/v1/admin/accounts/" + user.getId() + "/purge"),
                Map.of("confirm", user.getUsername() + "x"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(wrong).get("code").asText()).isEqualTo("account/confirmation-mismatch");
        assertThat(status(user)).isEqualTo("ACTIVE");

        MvcResult self = perform(admin, post("/api/v1/admin/accounts/" + admin.getId() + "/purge"),
                Map.of("confirm", admin.getUsername()));
        assertThat(self.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(self).get("code").asText()).isEqualTo("account/self-delete");

        // The old query-parameter form no longer purges (and no longer exists): 400, account untouched.
        for (String mode : List.of("hard", "purge")) {
            MvcResult old = perform(admin, delete("/api/v1/admin/accounts/" + user.getId()).param("mode", mode)
                    .param("confirm", user.getUsername()), null);
            assertThat(old.getResponse().getStatus()).as(mode).isEqualTo(400);
        }
        assertThat(status(user)).isEqualTo("ACTIVE");

        MvcResult hard = perform(admin, post("/api/v1/admin/accounts/" + user.getId() + "/purge"),
                Map.of("confirm", user.getUsername()));
        assertThat(hard.getResponse().getStatus()).isEqualTo(204);
        assertThat(hard.getRequest().getQueryString()).isNull();
        assertThat(status(user)).isNull();
        assertThat(count("SELECT count(*) FROM enrollments WHERE account_id = ?", user.getId())).isZero();
        assertThat(count("SELECT count(*) FROM security_event WHERE type = 'ACCOUNT_PURGED' AND actor_account_id = ? "
                + "AND target_pseudonym = ? AND details ->> 'trigger' = 'ADMIN_HARD_DELETE'", admin.getId(),
                pseudonyms.ofAccount(user.getId()))).isEqualTo(1);
        // The purged account's token no longer authenticates.
        assertThat(perform(user, get("/api/v1/me"), null).getResponse().getStatus()).isEqualTo(401);
        MvcResult gone = perform(admin, post("/api/v1/admin/accounts/" + user.getId() + "/purge"),
                Map.of("confirm", user.getUsername()));
        assertThat(gone.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void adminSoftDeleteListsAndRestoresAccounts() throws Exception {
        Account admin = account(Role.ADMIN);
        Account deactivated = account(Role.USER);
        Account pending = account(Role.USER);
        assertThat(requestDeletion(pending, PASSWORD).getResponse().getStatus()).isEqualTo(202);
        MvcResult before = login(deactivated, PASSWORD);
        String oldCookie = refreshCookie(before);
        String oldToken = accessToken(before);

        assertThat(perform(admin, delete("/api/v1/admin/accounts/" + deactivated.getId()), null)
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(status(deactivated)).isEqualTo("DEACTIVATED");
        // R-20: the soft delete is a session boundary.
        assertThat(count("SELECT count(*) FROM refresh_token_family WHERE account_id = ? AND revoked_at IS NULL",
                deactivated.getId())).isZero();
        // A soft delete leaves an owner's pending deletion untouched.
        assertThat(perform(admin, delete("/api/v1/admin/accounts/" + pending.getId()).param("mode", "soft"), null)
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(status(pending)).isEqualTo("PENDING_DELETION");

        JsonNode deletedList = body(perform(admin, get("/api/v1/admin/accounts").param("deleted", "true")
                .param("size", "100").param("search", "Fixture"), null)).get("content");
        Map<Long, String> listed = new java.util.HashMap<>();
        deletedList.forEach(item -> listed.put(item.get("id").asLong(), item.get("status").asText()));
        assertThat(listed).containsEntry(deactivated.getId(), "DEACTIVATED")
                .containsEntry(pending.getId(), "PENDING_DELETION").doesNotContainKey(admin.getId());

        for (Account account : List.of(deactivated, pending)) {
            MvcResult restored = perform(admin, post("/api/v1/admin/accounts/" + account.getId() + "/restore"), null);
            assertThat(restored.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(restored).get("status").asText()).isEqualTo("ACTIVE");
            assertThat(body(restored).get("deleteAfter").isNull()).isTrue();
            assertThat(status(account)).isEqualTo("ACTIVE");
        }
        assertThat(count("SELECT count(*) FROM security_event WHERE type = 'ACCOUNT_RESTORED' AND actor_account_id = ? "
                + "AND target_account_id IN (?, ?)", admin.getId(), deactivated.getId(), pending.getId())).isEqualTo(2);
        assertThat(perform(pending, get("/api/v1/me/enrollments"), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(perform(admin, post("/api/v1/admin/accounts/" + Long.MAX_VALUE + "/restore"), null)
                .getResponse().getStatus()).isEqualTo(404);

        // Restored, but nothing from before the soft delete comes back: refresh cookie and access token stay dead.
        MvcResult revived = refresh(oldCookie);
        assertThat(revived.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(revived).get("code").asText()).isEqualTo("auth/invalid-refresh-token");
        assertThat(mockMvc.perform(get("/api/v1/me").with(from(newIp()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(login(deactivated, PASSWORD).getResponse().getStatus()).as("signing in again works").isEqualTo(200);
    }

    // ---- session boundaries (R-16, R-20, AC-10) -----------------------------------------------------------

    /**
     * A bearer token issued before the deletion request (e.g. stolen) stops working at once and can never restore
     * the account: restore needs the current password.
     */
    @Test
    void aPreDeletionAccessTokenCannotRestoreTheAccount() throws Exception {
        Account user = account(Role.USER);
        MvcResult session = login(user, PASSWORD);
        String stolen = accessToken(session);
        assertThat(bearerRequest(get("/api/v1/me"), stolen).getResponse().getStatus()).isEqualTo(200);

        assertThat(requestDeletion(user, PASSWORD).getResponse().getStatus()).isEqualTo(202);

        assertThat(bearerRequest(get("/api/v1/me"), stolen).getResponse().getStatus())
                .as("the epoch moved on: the old token is dead, not merely restricted").isEqualTo(401);
        MvcResult replay = bearerRequest(post("/api/v1/me/restore").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD))), stolen);
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
        assertThat(status(user)).isEqualTo("PENDING_DELETION");
    }

    @Test
    void restoreRequiresTheCurrentPasswordAndCountsFailuresTowardsTheLockout() throws Exception {
        Account user = account(Role.USER);
        assertThat(requestDeletion(user, PASSWORD).getResponse().getStatus()).isEqualTo(202);
        String usernameHash = usernameHasher.hash(user.getUsername());
        long failuresBefore = count("SELECT count(*) FROM login_attempt WHERE username_hash = ? AND NOT success",
                usernameHash);

        MvcResult missing = perform(user, post("/api/v1/me/restore"), Map.of());
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(missing).get("code").asText()).isEqualTo("request/invalid");
        assertThat(perform(user, post("/api/v1/me/restore"), null).getResponse().getStatus()).isEqualTo(400);
        MvcResult wrong = restore(user, "not-the-current-password");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(wrong).get("code").asText()).isEqualTo("auth/invalid-current-password");
        assertThat(count("SELECT count(*) FROM login_attempt WHERE username_hash = ? AND NOT success", usernameHash))
                .isEqualTo(failuresBefore + 1);
        assertThat(count("SELECT count(*) FROM security_event WHERE type = 'AUTH_LOGIN_FAILURE' "
                + "AND target_account_id = ? AND details ->> 'reason' = 'restore_bad_current'", user.getId()))
                .isEqualTo(1);
        assertThat(status(user)).isEqualTo("PENDING_DELETION");
    }

    /**
     * The owner's restore ends the grace-period session (refresh family and access tokens) and answers a new
     * full session, like a login.
     */
    @Test
    void restoreEndsEveryEarlierSessionAndAnswersANewOne() throws Exception {
        Account user = account(Role.USER);
        assertThat(requestDeletion(user, PASSWORD).getResponse().getStatus()).isEqualTo(202);
        MvcResult graceLogin = login(user, PASSWORD);
        String graceToken = accessToken(graceLogin);
        String graceCookie = refreshCookie(graceLogin);

        MvcResult restored = bearerRequest(post("/api/v1/me/restore").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD))), graceToken);
        assertThat(restored.getResponse().getStatus()).isEqualTo(200);
        String newToken = accessToken(restored);
        String newCookie = refreshCookie(restored);

        assertThat(bearerRequest(get("/api/v1/me/enrollments"), graceToken).getResponse().getStatus())
                .as("the grace-period token does not become a full token").isEqualTo(401);
        assertThat(refresh(graceCookie).getResponse().getStatus()).isEqualTo(401);
        assertThat(bearerRequest(get("/api/v1/me/enrollments"), newToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(refresh(newCookie).getResponse().getStatus()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM security_event WHERE type = 'ACCOUNT_RESTORED' AND target_account_id = ? "
                + "AND (details ->> 'revokedRefreshTokens')::int >= 1", user.getId())).isEqualTo(1);
    }

    private MvcResult restore(Account account, String currentPassword) throws Exception {
        return perform(account, post("/api/v1/me/restore"), Map.of("currentPassword", currentPassword));
    }

    private MvcResult bearerRequest(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                    String token) throws Exception {
        return mockMvc.perform(request.with(from(newIp())).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    private long webhookSubscription() {
        return jdbc.queryForObject("INSERT INTO webhook_subscription "
                        + "(url, events, secret_encrypted, active, created_at, updated_at) "
                        + "VALUES (?, ARRAY['account.deleted'], 'v1:lifecycle-fixture', true, now(), now()) RETURNING id",
                Long.class, WEBHOOK_URL + UUID.randomUUID());
    }
}
