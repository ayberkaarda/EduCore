package com.educore.ingestion;

import com.educore.entity.Account;
import com.educore.entity.JobLog;
import com.educore.entity.JobLogStatus;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.educore.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** {@code POST /api/v1/admin/imports}: same validation as the inbox, written into the inbox, audited. */
class ImportUploadIT extends IngestionIntegrationSupport {

    @Autowired
    private ImportUploadService uploadService;

    @Autowired
    private IngestionRecovery recovery;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private UploadStaging stagingRows;

    private MvcResult upload(Account caller, String fileName, byte[] content) throws Exception {
        return perform(caller, multipart("/api/v1/admin/imports")
                .file(new MockMultipartFile("file", fileName, "text/csv", content)), null);
    }

    @Test
    void anAdminUploadIsWrittenToTheInboxImportedAndAudited() throws Exception {
        Account admin = account(Role.ADMIN);
        String token = token();
        String course = courseName("upload");
        byte[] content = (COURSE_HEADER + course + ",2026/2,Instructor U\n").getBytes(StandardCharsets.UTF_8);

        MvcResult result = upload(admin, "My Courses " + token + ".csv", content);

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        JsonNode body = body(result);
        assertThat(body.get("kind").asText()).isEqualTo("COURSES");
        assertThat(body.get("rows").asInt()).isEqualTo(1);
        assertThat(body.get("size").asLong()).isEqualTo(content.length);
        assertThat(body.get("inboxFileName").asText()).matches("[0-9a-f]{8}_My_Courses_" + token + "\\.csv");
        JobLog log = awaitClosedJobLog(token, Duration.ofSeconds(30));
        assertThat(log.getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
        assertThat(courseRepository.findByName(course)).isPresent();
        Integer audited = jdbc.queryForObject("SELECT count(*) FROM security_event WHERE type = 'IMPORT_UPLOADED' "
                + "AND actor_account_id = ? AND details ->> 'kind' = 'COURSES'", Integer.class, admin.getId());
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void invalidUploadsAreRejectedWithProblemsAndNothingReachesTheInbox() throws Exception {
        Account admin = account(Role.ADMIN);
        String token = token();

        assertProblem(upload(admin, "a-" + token + ".csv", "wrong,header\n1,2\n".getBytes(StandardCharsets.UTF_8)),
                400, "import/invalid-header");
        assertProblem(upload(admin, "a-" + token + ".xlsx", (COURSE_HEADER + "x,y,z\n").getBytes(StandardCharsets.UTF_8)),
                400, "import/invalid-file-name");
        assertProblem(upload(admin, "a-" + token + ".csv",
                (COURSE_HEADER + "Çalışma,1,2\n").getBytes(StandardCharsets.ISO_8859_1)), 400, "import/not-utf8");
        assertProblem(upload(admin, "a-" + token + ".csv", new byte[0]), 400, "import/empty-file");
        assertProblem(upload(admin, "a-" + token + ".csv",
                (COURSE_HEADER + "x,1,2\n".repeat(201)).getBytes(StandardCharsets.UTF_8)), 400, "import/too-many-rows");

        assertThat(filesContaining(directories.inbox(), token)).isEmpty();
        assertThat(filesContaining(directories.staging(), token)).isEmpty();
        Integer audited = jdbc.queryForObject("SELECT count(*) FROM security_event WHERE type = 'IMPORT_UPLOADED' "
                + "AND actor_account_id = ?", Integer.class, admin.getId());
        assertThat(audited).isZero();
    }

    @Test
    void contentThatWasAlreadyImportedIsAConflict() throws Exception {
        Account admin = account(Role.ADMIN);
        String token = token();
        byte[] content = (COURSE_HEADER + courseName("dup-upload") + ",2026/1,I\n").getBytes(StandardCharsets.UTF_8);
        assertThat(importNow(staged("courses-" + token + ".csv", content)).getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);

        assertProblem(upload(admin, "again-" + token + ".csv", content), 409, "import/duplicate");
    }

    @Test
    void usersCannotUpload() throws Exception {
        MvcResult result = upload(account(Role.USER), "x.csv", (COURSE_HEADER + "x,1,2\n").getBytes(StandardCharsets.UTF_8));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(List.of(directories.inbox().toFile().list())).noneMatch(name -> name.endsWith("_x.csv"));
    }

    @Test
    void aRolledBackUploadNeverReachesTheInbox() throws Exception {
        Account admin = account(Role.ADMIN);
        String token = token();
        MockMultipartFile file = new MockMultipartFile("file", "rollback-" + token + ".csv", "text/csv",
                (COURSE_HEADER + courseName("rollback") + ",1,2\n").getBytes(StandardCharsets.UTF_8));
        authenticateAs(AuthenticatedUser.of(admin));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            uploadService.accept(file);
            assertThat(stagedContaining(token)).isTrue();
            assertThat(inboxContaining(token)).isFalse();
            status.setRollbackOnly();
        });

        assertThat(stagedContaining(token)).isFalse();
        assertThat(inboxContaining(token)).isFalse();
    }

    /**
     * A staged file without an {@code upload_staging} row (staged before V34) keeps the earlier rule, but only once
     * it is older than one lease: published when its {@code IMPORT_UPLOADED} event committed, else deleted.
     */
    @Test
    void unregisteredStagedUploadsArePublishedOnlyWhenTheirAuditEventCommitted() throws Exception {
        String token = token();
        String course = courseName("staged");
        String committed = directories.stage((COURSE_HEADER + course + ",1,2\n").getBytes(StandardCharsets.UTF_8),
                "committed-" + token + ".csv");
        String uncommitted = directories.stage((COURSE_HEADER + courseName("lost") + ",1,2\n")
                .getBytes(StandardCharsets.UTF_8), "uncommitted-" + token + ".csv");
        jdbc.update("INSERT INTO security_event (type, at, details) VALUES ('IMPORT_UPLOADED', now(), ?::jsonb)",
                "{\"uploadId\":\"" + committed + "\"}");
        // Fresh files are left alone (they may belong to an upload in flight on an older instance) ...
        recovery.recoverStagedUploads();
        assertThat(directories.stagedTokens()).contains(committed, uncommitted);
        // ... and handled once they are older than the lease.
        for (Path staged : filesContaining(directories.staging(), token)) {
            Files.setLastModifiedTime(staged, FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));
        }
        try {
            assertThat(recovery.recoverStagedUploads()).isGreaterThanOrEqualTo(1);

            assertThat(directories.stagedTokens()).doesNotContain(committed, uncommitted);
            JobLog log = awaitClosedJobLog("committed-" + token, Duration.ofSeconds(30));
            assertThat(log.getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
            assertThat(courseRepository.findByName(course)).isPresent();
            assertThat(inboxContaining("uncommitted-" + token)).isFalse();
        } finally {
            jdbc.update("DELETE FROM security_event WHERE details ->> 'uploadId' = ?", committed);
        }
    }

    /**
     * AC-16: instance B's recovery runs while instance A's upload transaction is still open. The upload's row
     * is committed with A's lease before the file is staged, so B leaves the file alone, and A's commit publishes
     * it. Before the fix, B saw no committed audit event and deleted A's staged file.
     */
    @Test
    void aSiblingRecoveryNeverDeletesAnUploadThatIsStillInFlight() throws Exception {
        Account admin = account(Role.ADMIN);
        String token = token();
        String course = courseName("in-flight");
        MockMultipartFile file = new MockMultipartFile("file", "inflight-" + token + ".csv", "text/csv",
                (COURSE_HEADER + course + ",1,2\n").getBytes(StandardCharsets.UTF_8));
        authenticateAs(AuthenticatedUser.of(admin));
        ExecutorService sibling = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                uploadService.accept(file);
                assertThat(stagedContaining(token)).isTrue();
                runOn(sibling, () -> recovery.recoverStagedUploads());
                assertThat(stagedContaining(token)).as("sibling recovery must not touch an upload in flight").isTrue();
            });
        } finally {
            sibling.shutdownNow();
        }

        assertThat(stagedContaining(token)).isFalse();
        JobLog log = awaitClosedJobLog("inflight-" + token, Duration.ofSeconds(30));
        assertThat(log.getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
        assertThat(courseRepository.findByName(course)).isPresent();
    }

    /**
     * Two simulated owners: uploads of another instance are recovered only after that instance's lease expired;
     * then a COMMITTED one is published and a STAGING one discarded, each by exactly one conditional take-over.
     */
    @Test
    void uploadsOfAnotherOwnerAreRecoveredOnlyAfterItsLeaseExpired() throws Exception {
        String token = token();
        String course = courseName("abandoned");
        Instant now = Instant.now();
        String committed = UUID.randomUUID().toString();
        String staging = UUID.randomUUID().toString();
        stagingRows.begin(committed, "instance-other", now.plusSeconds(600), now);
        directories.stage(committed, (COURSE_HEADER + course + ",1,2\n").getBytes(StandardCharsets.UTF_8),
                "abandoned-committed-" + token + ".csv");
        jdbc.update("UPDATE upload_staging SET state = 'COMMITTED' WHERE token = ?::uuid", committed);
        stagingRows.begin(staging, "instance-other", now.plusSeconds(600), now);
        directories.stage(staging, (COURSE_HEADER + courseName("abandoned-lost") + ",1,2\n")
                .getBytes(StandardCharsets.UTF_8), "abandoned-staging-" + token + ".csv");

        // The other instance is alive: nothing is touched, whatever the state.
        recovery.recoverStagedUploads();
        assertThat(directories.stagedTokens()).contains(committed, staging);

        // It stopped renewing: the committed upload is published, the uncommitted one discarded, rows removed.
        jdbc.update("UPDATE upload_staging SET lease_until = ? WHERE token IN (?::uuid, ?::uuid)",
                Timestamp.from(now.minusSeconds(1)), committed, staging);
        assertThat(recovery.recoverStagedUploads()).isGreaterThanOrEqualTo(1);
        assertThat(directories.stagedTokens()).doesNotContain(committed, staging);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_staging WHERE token IN (?::uuid, ?::uuid)",
                Integer.class, committed, staging)).isZero();
        JobLog log = awaitClosedJobLog("abandoned-committed-" + token, Duration.ofSeconds(30));
        assertThat(log.getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
        assertThat(inboxContaining("abandoned-staging-" + token)).isFalse();
        // A late owner can no longer commit the upload recovery took over.
        assertThat(jdbc.update("UPDATE upload_staging SET state = 'COMMITTED' WHERE token = ?::uuid", staging)).isZero();
    }

    private static <T> T runOn(ExecutorService executor, java.util.concurrent.Callable<T> task) {
        try {
            return executor.submit(task).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean stagedContaining(String token) {
        try {
            return !filesContaining(directories.staging(), token).isEmpty();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private boolean inboxContaining(String token) {
        try {
            return !filesContaining(directories.inbox(), token).isEmpty();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void assertProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(body(result).get("code").asText()).isEqualTo(code);
    }
}
