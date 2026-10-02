package com.educore.ingestion;

import com.educore.entity.JobLog;
import com.educore.entity.JobLogStatus;
import com.educore.lifecycle.AccountPurger;
import com.educore.lifecycle.ErasureLedger;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-08: CSV files kept on disk are erased. With processed files retained ({@code retain-processed-days = 3}, the
 * default is 0 = delete right after the import, covered by {@code StudentImportJobIT} and
 * {@code IngestionDirectoryProtocolIT}): PARTIAL imports keep the file and its masked report in {@code done/} until
 * the cleanup removes them; FAILED files are kept at most {@code retain-failed-days}; and a purged student
 * disappears from every kept file, from the masked job log rows and from the webhook delivery history.
 */
class IngestionRetentionIT extends IngestionIntegrationSupport {

    private static final String WEBHOOK_URL = "https://ingestion-it.example.com/retention";

    @Autowired
    private IngestionRetention retention;

    @Autowired
    private AccountPurger purger;

    @Autowired
    private ErasureLedger ledger;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Path> created = new ArrayList<>();

    @DynamicPropertySource
    static void retainProcessedFiles(DynamicPropertyRegistry registry) {
        registry.add("educore.ingestion.retain-processed-days", () -> "3");
        registry.add("educore.ingestion.retain-failed-days", () -> "7");
    }

    @AfterEach
    void removeFiles() throws IOException {
        for (Path file : created) {
            Files.deleteIfExists(file);
        }
        created.clear();
    }

    @Test
    void aRetainedPartialImportKeepsAMaskedReportUntilTheCleanupRemovesBoth() throws IOException {
        String token = token();
        String repeated = number();
        String other = number();

        JobLog log = importNow(staged("students-keep-" + token + ".csv", STUDENT_HEADER
                + "Ayşe,Yılmaz," + repeated + "\n" + "Ali,Kaya," + other + "\n" + "Elif,Şahin," + repeated + "\n"));

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.PARTIAL);
        List<Path> kept = filesContaining(directories.done(), token);
        assertThat(kept).hasSize(2);
        Path report = kept.stream().filter(path -> path.toString().endsWith(".report.json")).findFirst().orElseThrow();
        JsonNode json = this.json.readTree(Files.readString(report, StandardCharsets.UTF_8));
        assertThat(json.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(json.get("entries").get(0).get("reason").asText()).isEqualTo("DUPLICATE_IN_FILE");
        assertThat(Files.readString(report, StandardCharsets.UTF_8)).doesNotContain(repeated).doesNotContain("Yılmaz");

        retention.cleanup();
        assertThat(filesContaining(directories.done(), token)).hasSize(2);

        for (Path file : kept) {
            age(file, Duration.ofDays(4));
        }
        retention.cleanup();
        assertThat(filesContaining(directories.done(), token)).isEmpty();
    }

    @Test
    void failedFilesAndQuarantinedEntriesAreKeptAtMostTheFailedRetention() throws IOException {
        String token = token();
        Path oldCsv = failedFile("old-" + token + ".csv", STUDENT_HEADER + "Eski,Kayıt,97100001\n", Duration.ofDays(8));
        Path oldReport = failedFile("old-" + token + ".report.json", "{\"status\":\"FAILED\"}", Duration.ofDays(8));
        Path recent = failedFile("recent-" + token + ".csv", STUDENT_HEADER + "Yeni,Kayıt,97100002\n",
                Duration.ofDays(1));
        Path quarantined = Files.createDirectory(directories.failed().resolve("dir-" + token + ".csv"));
        Path inside = Files.writeString(quarantined.resolve("inner.csv"), "x", StandardCharsets.UTF_8);
        age(inside, Duration.ofDays(8));
        age(quarantined, Duration.ofDays(8));

        IngestionRetention.Cleanup result = retention.cleanup();

        assertThat(result.failed()).isGreaterThanOrEqualTo(3);
        assertThat(oldCsv).doesNotExist();
        assertThat(oldReport).doesNotExist();
        assertThat(quarantined).doesNotExist();
        assertThat(recent).exists();
    }

    /**
     * The purge of an imported student removes every residual trace outside the account tables: the student's
     * lines in kept CSV files (other students' lines and course files stay byte for byte), the masked job log row
     * of the student's own line, and the webhook delivery history naming the account; the erasure ledger records
     * the purge. Before the fix all of these survived the purge.
     */
    @Test
    void aPurgeRemovesTheStudentFromKeptFilesMaskedRowsAndDeliveryHistory() throws IOException {
        String token = token();
        String erased = number();
        String kept = number();
        JobLog log = importNow(staged("students-purge-" + token + ".csv", STUDENT_HEADER
                + "Zeynep,Erased," + erased + "\n" + "Mehmet,Kept," + kept + "\n"));
        assertThat(log.getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
        Path done = filesContaining(directories.done(), token).get(0);
        created.add(done);
        Path failed = failedFile("students-retry-" + token + ".csv", STUDENT_HEADER
                + "\"Zeynep\",\"Erased\",\"" + erased + "\"\r\n" + "Mehmet,Kept," + kept + "\r\n", Duration.ofDays(1));
        String courses = COURSE_HEADER + "Course " + token + "," + erased + ",Instructor\n";
        Path courseFile = failedFile("courses-" + token + ".csv", courses, Duration.ofDays(1));
        FileTime failedModified = Files.getLastModifiedTime(failed);

        long accountId = accountRepository.findByStudentNumber(erased).orElseThrow().getId();
        long keptId = accountRepository.findByStudentNumber(kept).orElseThrow().getId();
        String ownMask = PiiMasker.mask("Zeynep,Erased," + erased);
        jdbc.update("INSERT INTO job_log_entry (job_log_id, row_number, level, reason, raw_masked) "
                + "VALUES (?, 2, 'WARN', 'ALREADY_EXISTS', ?)", log.getId(), ownMask);
        long subscription = jdbc.queryForObject("INSERT INTO webhook_subscription "
                + "(url, events, secret_encrypted, active, created_at, updated_at) "
                + "VALUES (?, ARRAY['account.deleted'], 'v1:unused', true, now(), now()) RETURNING id", Long.class,
                WEBHOOK_URL + "/" + token);
        insertDelivery(subscription, accountId);
        insertDelivery(subscription, keptId);

        AccountPurger.Result result = new TransactionTemplate(transactionManager)
                .execute(status -> purger.purge(accountId, AccountPurger.Trigger.ADMIN_HARD_DELETE));

        assertThat(result).isNotNull();
        assertThat(Files.readString(done, StandardCharsets.UTF_8)).doesNotContain(erased).doesNotContain("Zeynep")
                .contains("Mehmet,Kept," + kept).startsWith(STUDENT_HEADER);
        assertThat(Files.readString(failed, StandardCharsets.UTF_8)).isEqualTo(STUDENT_HEADER
                + "Mehmet,Kept," + kept + "\r\n");
        assertThat(Files.getLastModifiedTime(failed)).isEqualTo(failedModified);
        assertThat(Files.readString(courseFile, StandardCharsets.UTF_8)).isEqualTo(courses);
        assertThat(jdbc.queryForList("SELECT raw_masked FROM job_log_entry WHERE job_log_id = ? AND row_number = 2",
                String.class, log.getId())).containsExactly((String) null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE subscription_id = ? "
                + "AND event = 'account.updated' AND payload -> 'data' ->> 'accountId' = ?", Integer.class, subscription,
                String.valueOf(accountId))).as("delivery history of the purged account").isZero();
        // What remains about it is the deletion notice queued after the commit (ids only, 14-day retention).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE subscription_id = ? "
                + "AND event = 'account.deleted' AND payload -> 'data' ->> 'accountId' = ?", Integer.class, subscription,
                String.valueOf(accountId))).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE subscription_id = ? "
                + "AND payload -> 'data' ->> 'accountId' = ?", Integer.class, subscription, String.valueOf(keptId)))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM erasure_ledger WHERE account_digest = ?", Integer.class,
                ledger.accountDigest(accountId))).isOne();
    }

    private void insertDelivery(long subscription, long accountId) {
        jdbc.update("INSERT INTO webhook_delivery (id, subscription_id, event, payload, status, created_at) "
                + "VALUES (gen_random_uuid(), ?, 'account.updated', ?::jsonb, 'DELIVERED', now())", subscription,
                "{\"event\":\"account.updated\",\"data\":{\"accountId\":" + accountId + "}}");
    }

    private Path failedFile(String name, String content, Duration age) throws IOException {
        Path file = Files.writeString(directories.failed().resolve(name), content, StandardCharsets.UTF_8);
        created.add(file);
        age(file, age);
        return file;
    }

    private static void age(Path file, Duration age) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)));
    }
}
