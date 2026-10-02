package com.educore.ingestion;

import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.entity.JobLog;
import com.educore.entity.JobLogStatus;
import com.educore.entity.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The student (and course) import jobs end to end through {@link IngestionService#ingest}: strict parsing,
 * Bean Validation, deduplication inside the file and against the database, skip reporting with masked raw
 * lines, the SUCCEEDED/PARTIAL/FAILED rules, pre-launch rejections and file-level idempotency.
 */
@ExtendWith(OutputCaptureExtension.class)
class StudentImportJobIT extends IngestionIntegrationSupport {

    @Test
    void happyPathImportsEveryRowWithItsOwnTemporaryPassword() throws IOException {
        String token = token();
        String first = number();
        String second = number();
        String third = number();
        Path file = staged("students-" + token + ".csv", STUDENT_HEADER
                + "Ayşe,Yılmaz," + first + "\n" + "Ali,Kaya," + second + "\n" + "Zeynep,Demir," + third + "\n");

        JobLog log = importNow(file);

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
        assertThat(log.getReason()).isNull();
        assertThat(log.getEntityType()).isEqualTo("STUDENTS");
        assertThat(log.getReadRecords()).isEqualTo(3);
        assertThat(log.getSuccessfulRecords()).isEqualTo(3);
        assertThat(log.getFailedRecords()).isZero();
        assertThat(log.getStartedAt()).isNotNull();
        assertThat(log.getFinishedAt()).isAfterOrEqualTo(log.getStartedAt());
        List<Account> accounts = List.of(first, second, third).stream()
                .map(number -> accountRepository.findByStudentNumber(number).orElseThrow()).toList();
        assertThat(accounts).allSatisfy(account -> {
            assertThat(account.getUsername()).isEqualTo(account.getStudentNumber());
            assertThat(account.getRole()).isEqualTo(Role.USER);
            assertThat(account.getPassword()).startsWith("{bcrypt}$2a$12$");
            assertThat(account.isMustChangePassword()).isTrue();
            assertThat(passwordEncoder.matches("REMOVED-DB-PASSWORD", account.getPassword())).isFalse();
            assertThat(passwordEncoder.matches("REMOVED-DB-PASSWORD56", account.getPassword())).isFalse();
        });
        assertThat(accounts).extracting(Account::getPassword).doesNotHaveDuplicates();
        assertThat(accounts.get(0).getFirstName()).isEqualTo("Ayşe");

        ImportedFile imported = importedFileRepository.findById(log.getImportedFileId()).orElseThrow();
        assertThat(imported.getStatus()).isEqualTo(ImportedFile.Status.SUCCEEDED);
        assertThat(imported.getKind()).isEqualTo(CsvKind.STUDENTS);
        assertThat(imported.getRows()).isEqualTo(3);
        assertThat(imported.getSha256()).hasSize(64);
        assertThat(imported.getJobExecutionId()).isNotNull();

        // Erasure by default (AC-08): the imported file is not kept anywhere once its rows are in the database.
        assertThat(filesContaining(directories.done(), token)).isEmpty();
        assertThat(filesContaining(directories.failed(), token)).isEmpty();
        assertThat(filesContaining(directories.processing(), token)).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void quotedFieldsWithDelimitersQuotesAndSpacesAreParsed() throws IOException {
        String token = token();
        String number = number();
        String courseOne = courseName("Intro, Part");
        String courseTwo = courseName("Lab");
        Path students = staged("students-q-" + token + ".csv", STUDENT_HEADER
                + "\"Mary-Jane\",\"O'Neil\",\"" + number + "\"\n");
        Path courses = staged("courses-q-" + token + ".csv", COURSE_HEADER
                + "\"" + courseOne + "\",\"2026/1\",\"Instructor \"\"Alpha\"\"\"\n"
                + courseTwo + ",, Instructor Beta \n");

        assertThat(importNow(students).getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
        JobLog courseLog = importNow(courses);

        Account account = accountRepository.findByStudentNumber(number).orElseThrow();
        assertThat(account.getFirstName()).isEqualTo("Mary-Jane");
        assertThat(account.getLastName()).isEqualTo("O'Neil");
        assertThat(courseLog.getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
        assertThat(courseLog.getEntityType()).isEqualTo("COURSES");
        Course first = courseRepository.findByName(courseOne).orElseThrow();
        assertThat(first.getInstructor()).isEqualTo("Instructor \"Alpha\"");
        Course second = courseRepository.findByName(courseTwo).orElseThrow();
        assertThat(second.getTerm()).isNull();
        assertThat(second.getInstructor()).isEqualTo("Instructor Beta");
    }

    @Test
    void inFileDuplicateIsSkippedReportedMaskedAndMakesTheImportPartial() throws IOException {
        String token = token();
        String repeated = number();
        String other = number();
        Path file = staged("students-dup-" + token + ".csv", STUDENT_HEADER
                + "Ayşe,Yılmaz," + repeated + "\n" + "Ali,Kaya," + other + "\n" + "Elif,Şahin," + repeated + "\n");

        JobLog log = importNow(file);

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.PARTIAL);
        assertThat(log.getSuccessfulRecords()).isEqualTo(2);
        assertThat(log.getFailedRecords()).isEqualTo(1);
        List<JobLogEntry> entries = entries(log);
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getReason()).isEqualTo("DUPLICATE_IN_FILE");
            assertThat(entry.getLevel()).isEqualTo(JobLogEntry.Level.WARN);
            assertThat(entry.getRowNumber()).isIn(2, 4);
            assertThat(entry.getRawMasked()).doesNotContain(repeated).doesNotContain("Yılmaz").doesNotContain("Şahin")
                    .matches("[AE]\\*\\*\\*,[YŞ]\\*\\*\\*,9\\*\\*\\*");
        });
        assertThat(accountRepository.findByStudentNumber(repeated)).isPresent();

        // Neither the file nor a report is kept by default; the masked entry above is what remains
        // (IngestionRetentionIT covers the report written when processed files are retained).
        assertThat(filesContaining(directories.done(), token)).isEmpty();
        assertThat(filesContaining(directories.processing(), token)).isEmpty();
    }

    @Test
    void rowsThatAlreadyExistInTheDatabaseAreSkipped() throws IOException {
        String token = token();
        Account existing = account(Role.USER);
        String fresh = number();
        Path partial = staged("students-db-" + token + ".csv", STUDENT_HEADER
                + "Ali,Kaya," + existing.getStudentNumber() + "\n" + "Can,Ak," + fresh + "\n");
        Path nothingNew = staged("students-db2-" + token + ".csv", STUDENT_HEADER
                + "Ali,Kaya," + existing.getStudentNumber() + "\n");

        JobLog partialLog = importNow(partial);
        JobLog failedLog = importNow(nothingNew);

        assertThat(partialLog.getStatus()).isEqualTo(JobLogStatus.PARTIAL);
        assertThat(entries(partialLog)).extracting(JobLogEntry::getReason).containsExactly("ALREADY_EXISTS");
        assertThat(entries(partialLog)).extracting(JobLogEntry::getRowNumber).containsExactly(2);
        assertThat(accountRepository.findById(existing.getId()).orElseThrow().getFirstName()).isEqualTo("Fixture");
        assertThat(failedLog.getStatus()).isEqualTo(JobLogStatus.FAILED);
        assertThat(failedLog.getReason()).isEqualTo("NO_ROWS_WRITTEN");
        assertThat(filesContaining(directories.failed(), "students-db2-" + token)).hasSize(2);
    }

    @Test
    void invalidRowsAreSkippedWithTheirFieldNamesOrParseReason() throws IOException {
        String token = token();
        String valid = number();
        Path file = staged("students-bad-" + token + ".csv", STUDENT_HEADER
                + "Ali,Kaya," + valid + "\n"
                + "Veli,Kaya,12ab\n"
                + "<script>,Kaya," + number() + "\n"
                + "Only,TwoColumns\n"
                + "\"Unclosed,Kaya,REMOVED-DB-PASSWORD56\n");

        JobLog log = importNow(file);

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.PARTIAL);
        assertThat(log.getSuccessfulRecords()).isEqualTo(1);
        assertThat(entries(log)).extracting(JobLogEntry::getReason)
                .contains("INVALID_FIELD:studentNumber", "INVALID_FIELD:firstName", "WRONG_COLUMN_COUNT")
                .hasSize(4);
        assertThat(entries(log)).filteredOn(entry -> entry.getReason().equals("WRONG_COLUMN_COUNT"))
                .extracting(JobLogEntry::getRowNumber).containsExactly(5);
    }

    @Test
    void moreSkipsThanTheLimitFailTheJob() throws IOException {
        String token = token();
        StringBuilder rows = new StringBuilder(STUDENT_HEADER).append("Ali,Kaya,").append(number()).append('\n');
        for (int i = 0; i < 30; i++) {
            rows.append("Bad,Row,x").append(i).append('\n');
        }

        JobLog log = importNow(staged("students-limit-" + token + ".csv", rows.toString()));

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.FAILED);
        assertThat(log.getReason()).isEqualTo("SKIP_LIMIT_EXCEEDED");
        assertThat(filesContaining(directories.failed(), token)).hasSize(2);
    }

    @Test
    void invalidHeaderIsRejectedBeforeTheJobStarts() throws IOException {
        String token = token();

        JobLog log = importNow(staged("students-header-" + token + ".csv",
                "firstName,lastName,studentNumber\nAli,Kaya," + number() + "\n"));

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.FAILED);
        assertThat(log.getReason()).isEqualTo("INVALID_HEADER");
        assertThat(log.getImportedFileId()).isNull();
        assertThat(log.getEntityType()).isNull();
        assertThat(entries(log)).singleElement().satisfies(entry -> {
            assertThat(entry.getRowNumber()).isNull();
            assertThat(entry.getLevel()).isEqualTo(JobLogEntry.Level.ERROR);
            assertThat(entry.getReason()).isEqualTo("INVALID_HEADER");
        });
        List<Path> failed = filesContaining(directories.failed(), token);
        assertThat(failed).hasSize(2);
        assertThat(failed).anySatisfy(path -> assertThat(path.toString()).endsWith(".report.json"));
    }

    @Test
    void oversizeFileIsRejected() throws IOException {
        String token = token();
        String row = "Ali,Kaya,97000000\n";
        String content = STUDENT_HEADER + row.repeat(65 * 1024 / row.length() + 1);

        JobLog log = importNow(staged("students-big-" + token + ".csv", content));

        assertThat(content.length()).isGreaterThan(64 * 1024);
        assertThat(log.getStatus()).isEqualTo(JobLogStatus.FAILED);
        assertThat(log.getReason()).isEqualTo("FILE_TOO_LARGE");
        assertThat(log.getReadRecords()).isZero();
    }

    @Test
    void nonUtf8FileIsRejected() throws IOException {
        String token = token();
        byte[] latin1 = (STUDENT_HEADER + "Çağrı,Öztürk," + number() + "\n").getBytes(StandardCharsets.ISO_8859_1);

        JobLog log = importNow(staged("students-latin1-" + token + ".csv", latin1));

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.FAILED);
        assertThat(log.getReason()).isEqualTo("NOT_UTF8");
    }

    @Test
    void theSameContentIsImportedOnceButAFailedImportCanBeRetried() throws IOException {
        String token = token();
        String content = STUDENT_HEADER + "Ali,Kaya," + number() + "\n";
        Account blocker = account(Role.USER);
        String retryContent = STUDENT_HEADER + "Ali,Kaya," + blocker.getStudentNumber() + "\n";

        JobLog first = importNow(staged("students-a-" + token + ".csv", content));
        JobLog again = importNow(staged("students-b-" + token + ".csv", content));
        JobLog failing = importNow(staged("students-c-" + token + ".csv", retryContent));
        jdbc.update("DELETE FROM account WHERE id = ?", blocker.getId());
        JobLog retried = importNow(staged("students-d-" + token + ".csv", retryContent));
        cleanUpStudentNumber(blocker.getStudentNumber());

        assertThat(first.getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
        assertThat(again.getStatus()).isEqualTo(JobLogStatus.FAILED);
        assertThat(again.getReason()).isEqualTo("DUPLICATE");
        assertThat(failing.getStatus()).isEqualTo(JobLogStatus.FAILED);
        assertThat(retried.getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
        assertThat(retried.getImportedFileId()).isEqualTo(failing.getImportedFileId());
    }

    @Test
    void importOutcomesAreQueuedAsWebhookEventsWithoutPersonalData() throws IOException {
        String token = token();
        jdbc.update("INSERT INTO webhook_subscription (url, events, secret_encrypted, active, created_at, updated_at) "
                + "VALUES (?, ARRAY['import.completed','import.failed'], 'v1:it-fixture', true, now(), now())",
                "https://ingestion-it.example.com/" + token);
        String number = number();

        JobLog ok = importNow(staged("students-hook-" + token + ".csv", STUDENT_HEADER + "Ali,Kaya," + number + "\n"));
        JobLog failed = importNow(staged("students-hook2-" + token + ".csv", "wrong,header,line\nA,B,C\n"));

        List<Map<String, Object>> deliveries = jdbc.queryForList("SELECT d.event, d.payload::text AS payload "
                + "FROM webhook_delivery d JOIN webhook_subscription s ON s.id = d.subscription_id "
                + "WHERE s.url = ? ORDER BY d.created_at", "https://ingestion-it.example.com/" + token);
        assertThat(deliveries).extracting(row -> row.get("event")).containsExactly("import.completed", "import.failed");
        assertThat(deliveries.get(0).get("payload").toString())
                .contains("\"jobLogId\": " + ok.getId()).contains("\"status\": \"SUCCEEDED\"")
                .doesNotContain(number).doesNotContain("Kaya").doesNotContain("students-hook");
        assertThat(deliveries.get(1).get("payload").toString())
                .contains("\"jobLogId\": " + failed.getId()).contains("\"reason\": \"INVALID_HEADER\"");
    }

    @Test
    void rowValuesNeverReachBatchFailureMetadataOrLogs(CapturedOutput output) throws IOException {
        String token = token();
        String canary = "Zqxcanary";
        StringBuilder rows = new StringBuilder(STUDENT_HEADER).append("Ali,Kaya,").append(number()).append('\n');
        for (int i = 0; i < 15; i++) {
            rows.append(canary).append(i).append(",Only\n");
            rows.append(canary).append(",Row,").append(canary).append(i).append('\n');
        }

        JobLog log = importNow(staged("students-canary-" + token + ".csv", rows.toString()));

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.FAILED);
        assertThat(log.getReason()).isEqualTo("SKIP_LIMIT_EXCEEDED");
        Long execution = jdbc.queryForObject("SELECT job_execution_id FROM imported_file WHERE id = ?", Long.class,
                log.getImportedFileId());
        assertThat(execution).isNotNull();
        List<String> batchTexts = new java.util.ArrayList<>();
        batchTexts.addAll(jdbc.queryForList("SELECT coalesce(exit_message, '') FROM batch_job_execution "
                + "WHERE job_execution_id = ?", String.class, execution));
        batchTexts.addAll(jdbc.queryForList("SELECT coalesce(exit_message, '') FROM batch_step_execution "
                + "WHERE job_execution_id = ?", String.class, execution));
        batchTexts.addAll(jdbc.queryForList("SELECT coalesce(parameter_value, '') FROM batch_job_execution_params "
                + "WHERE job_execution_id = ?", String.class, execution));
        batchTexts.addAll(jdbc.queryForList("SELECT coalesce(c.short_context, '') || coalesce(c.serialized_context, '') "
                + "FROM batch_step_execution_context c JOIN batch_step_execution s ON s.step_execution_id = "
                + "c.step_execution_id WHERE s.job_execution_id = ?", String.class, execution));
        batchTexts.addAll(jdbc.queryForList("SELECT coalesce(short_context, '') || coalesce(serialized_context, '') "
                + "FROM batch_job_execution_context WHERE job_execution_id = ?", String.class, execution));
        assertThat(batchTexts).isNotEmpty().allSatisfy(text -> assertThat(text).doesNotContain(canary));
        assertThat(String.join("\n", batchTexts)).contains("SkipLimitExceededException").doesNotContain("students-canary");
        assertThat(entries(log)).isNotEmpty().allSatisfy(entry ->
                assertThat(String.valueOf(entry.getRawMasked())).doesNotContain(canary));
        assertThat(output.getAll()).doesNotContain(canary);
    }

    private List<JobLogEntry> entries(JobLog log) {
        return entryRepository.findByJobLogId(log.getId(), PageRequest.of(0, 100, Sort.by("id"))).getContent();
    }
}
