package com.educore.ingestion;

import com.educore.entity.JobLog;
import com.educore.entity.JobLogStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.integration.file.filters.FileSystemPersistentAcceptOnceFileListFilter;
import org.springframework.integration.jdbc.metadata.JdbcMetadataStore;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The folder protocol with the real inbox poller: inbox -> processing/&lt;uuid&gt;_&lt;name&gt; -> done/ or failed/,
 * the stability window for half-written files, the *.csv filter, and restart safety (persistent accept-once
 * state in the database, recovery of imports interrupted while in processing/).
 */
class IngestionDirectoryProtocolIT extends IngestionIntegrationSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private IngestionRecovery recovery;

    @Autowired
    private IngestionLedger ledger;

    @Autowired
    private IngestionRetention retention;

    @Autowired
    private CsvPreLaunchValidator validator;

    @Autowired
    private FileSystemPersistentAcceptOnceFileListFilter acceptOnce;

    @Autowired
    @Qualifier(IngestionFlowConfig.INBOX_ADAPTER_ID)
    private SmartLifecycle adapter;

    /**
     * With the default {@code retain-processed-days = 0} (AC-08) a successful import keeps no copy of the file:
     * the snapshot is deleted instead of being moved to {@code done/}.
     */
    @Test
    void anInboxFileMovesThroughProcessingAndIsDeletedAfterASuccessfulImport() throws IOException {
        String token = token();
        String name = "courses-" + token + ".csv";
        String course = courseName("protocol");

        dropIntoInbox(name, (COURSE_HEADER + course + ",2026/1,Instructor P\n").getBytes(StandardCharsets.UTF_8));
        JobLog log = awaitClosedJobLog(token, TIMEOUT);

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
        assertThat(log.getFileName()).isEqualTo(name);
        assertThat(courseRepository.findByName(course)).isPresent();
        assertThat(directories.inbox().resolve(name)).doesNotExist();
        // The run is closed before the snapshot is deleted: wait for the folder, not for a fixed time.
        awaitFiles(directories.processing(), token, 0, TIMEOUT);
        awaitFiles(directories.done(), token, 0, TIMEOUT);
        awaitFiles(directories.failed(), token, 0, TIMEOUT);
        assertThat(filesContaining(directories.done(), token)).isEmpty();
        assertThat(filesContaining(directories.failed(), token)).isEmpty();
        assertThat(importedFileRepository.findById(log.getImportedFileId())).get()
                .satisfies(file -> assertThat(file.getSha256()).hasSize(64));
        Integer remembered = jdbc.queryForObject("SELECT count(*) FROM INT_METADATA_STORE WHERE REGION = ? "
                + "AND METADATA_KEY LIKE ?", Integer.class, IngestionFlowConfig.METADATA_REGION, "%" + token + "%");
        assertThat(remembered).isEqualTo(1);
    }

    @Test
    void aRejectedInboxFileGoesToFailedWithAReport() throws IOException {
        String token = token();
        String name = "students-" + token + ".csv";

        dropIntoInbox(name, "not,the,header\n1,2,3\n".getBytes(StandardCharsets.UTF_8));
        JobLog log = awaitClosedJobLog(token, TIMEOUT);

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.FAILED);
        assertThat(log.getReason()).isEqualTo("INVALID_HEADER");
        // The run is closed before the file is moved and its report written: wait for both files.
        List<Path> failed = awaitFiles(directories.failed(), token, 2, TIMEOUT);
        Path report = failed.stream().filter(path -> path.toString().endsWith(".report.json")).findFirst().orElseThrow();
        JsonNode json = this.json.readTree(Files.readString(report, StandardCharsets.UTF_8));
        assertThat(json.get("status").asText()).isEqualTo("FAILED");
        assertThat(json.get("reason").asText()).isEqualTo("INVALID_HEADER");
        assertThat(filesContaining(directories.done(), token)).isEmpty();
    }

    @Test
    void aFileStillBeingWrittenWaitsAndOtherExtensionsAreIgnored() throws IOException {
        String token = token();
        String course = courseName("stable");
        Path inProgress = directories.inbox().resolve("courses-" + token + ".csv");
        Path notes = directories.inbox().resolve("notes-" + token + ".txt");
        Files.writeString(inProgress, COURSE_HEADER + course + ",2026/1,Instructor S\n", StandardCharsets.UTF_8);
        Files.writeString(notes, "not a csv", StandardCharsets.UTF_8);
        // A writer that keeps touching the file: its modification time stays "now" for the next 3 seconds.
        Instant writtenUntil = Instant.now().plusSeconds(3);
        Files.setLastModifiedTime(inProgress, FileTime.from(writtenUntil));

        pause(Duration.ofMillis(1500));
        // Deterministic on a slow machine too: before writtenUntil the poller cannot have taken the file.
        if (Instant.now().isBefore(writtenUntil)) {
            assertThat(inProgress).exists();
        }
        JobLog log = awaitClosedJobLog(token, TIMEOUT);

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
        assertThat(log.getStartedAt()).isAfter(writtenUntil);
        // Both files were in the inbox during every scan that took the csv: the .txt was seen and left alone.
        assertThat(notes).exists();
        Files.delete(notes);
    }

    @Test
    void acceptOnceStateSurvivesARestartBecauseItIsStoredInTheDatabase() throws IOException {
        String token = token();
        Path dir = Files.createDirectories(BASE.resolve("restart-" + token));
        File file = Files.writeString(dir.resolve("courses-" + token + ".csv"), "x", StandardCharsets.UTF_8).toFile();

        FileSystemPersistentAcceptOnceFileListFilter beforeRestart = filterOfANewInstance();
        assertThat(beforeRestart.filterFiles(new File[]{file})).containsExactly(file);
        FileSystemPersistentAcceptOnceFileListFilter afterRestart = filterOfANewInstance();
        assertThat(afterRestart.filterFiles(new File[]{file})).isEmpty();

        // A changed file (new modification time) is picked up again.
        Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.now().plusSeconds(60)));
        assertThat(afterRestart.filterFiles(new File[]{file})).containsExactly(file);
        jdbc.update("DELETE FROM INT_METADATA_STORE WHERE METADATA_KEY LIKE ?", "%" + token + "%");
    }

    @Test
    void recoveryClosesOnlyRunsWhoseLeaseExpiredWhateverTheirOwner() throws IOException {
        String token = token();
        String deadSnapshot = IngestionDirectories.newSnapshotName("students-dead-" + token + ".csv");
        String liveSnapshot = IngestionDirectories.newSnapshotName("students-live-" + token + ".csv");
        Path dead = Files.writeString(directories.processing().resolve(deadSnapshot),
                STUDENT_HEADER + "Ali,Kaya,97000001\n", StandardCharsets.UTF_8);
        Path live = Files.writeString(directories.processing().resolve(liveSnapshot),
                STUDENT_HEADER + "Ali,Kaya,97000002\n", StandardCharsets.UTF_8);
        Instant now = Instant.now();
        long deadRun = ledger.open("students-dead-" + token + ".csv", now.minusSeconds(600), "instance-crashed",
                now.minusSeconds(60), deadSnapshot);
        long liveRun = ledger.open("students-live-" + token + ".csv", now, "instance-other-alive",
                now.plusSeconds(600), liveSnapshot);

        recovery.recover();
        recovery.recover();

        assertThat(dead).doesNotExist();
        Path report = filesContaining(directories.failed(), token).stream()
                .filter(path -> path.toString().endsWith(".report.json")).findFirst().orElseThrow();
        assertThat(Files.readString(report, StandardCharsets.UTF_8)).contains("INTERRUPTED");
        JobLog closed = jobLogRepository.findById(deadRun).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(JobLogStatus.FAILED);
        assertThat(closed.getReason()).isEqualTo("INTERRUPTED");
        assertThat(live).exists();
        assertThat(jobLogRepository.findById(liveRun).orElseThrow().getStatus()).isNull();

        // The other instance stops renewing: once its lease is over, its run is recovered too.
        jdbc.update("UPDATE job_log SET lease_until = ? WHERE id = ?", Timestamp.from(now.minusSeconds(1)), liveRun);
        recovery.recover();
        assertThat(live).doesNotExist();
        assertThat(jobLogRepository.findById(liveRun).orElseThrow().getReason()).isEqualTo("INTERRUPTED");
    }

    @Test
    void aFileAcceptedButNeverClaimedBeforeACrashIsReleasedAtStartup() throws IOException {
        String token = token();
        String course = courseName("crash");
        adapter.stop();
        try {
            Path file = Files.writeString(directories.inbox().resolve("courses-" + token + ".csv"),
                    COURSE_HEADER + course + ",2026/1,Instructor C\n", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(60)));
            // A poll accepted the file (mark stored in the database), then the process died before the claim.
            assertThat(acceptOnce.filterFiles(new File[]{file.toFile()})).hasSize(1);
            assertThat(acceptOnce.filterFiles(new File[]{file.toFile()})).isEmpty();

            assertThat(recovery.releaseInbox()).isGreaterThanOrEqualTo(1);
        } finally {
            adapter.start();
        }
        JobLog log = awaitClosedJobLog(token, TIMEOUT);
        assertThat(log.getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
        assertThat(courseRepository.findByName(course)).isPresent();
    }

    @Test
    void linksAndOtherNonRegularEntriesAreNeverFollowed() throws IOException {
        String token = token();
        Path target = Files.writeString(BASE.resolve("secret-" + token + ".csv"),
                COURSE_HEADER + courseName("linked") + ",1,2\n", StandardCharsets.UTF_8);
        Path entry = Files.createDirectories(BASE.resolve("fixtures")).resolve("courses-link-" + token + ".csv");
        try {
            Files.createSymbolicLink(entry, target);
        } catch (IOException | UnsupportedOperationException e) {
            // No symbolic-link privilege (e.g. Windows without developer mode): a directory named *.csv is the
            // other kind of non-regular entry the poller can meet, and it takes the same path.
            Files.createDirectory(entry);
        }

        JobLog log = importNow(entry);

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.FAILED);
        assertThat(log.getReason()).isEqualTo("NOT_A_REGULAR_FILE");
        assertThat(entry).doesNotExist();
        assertThat(filesContaining(directories.failed(), token)).hasSize(1);
        assertThat(target).exists();
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).startsWith(COURSE_HEADER);
    }

    @Test
    void contentWrittenAfterTheSnapshotIsNotImported() throws IOException {
        String token = token();
        String imported = courseName("snap");
        String appended = courseName("after");
        Path fixtures = Files.createDirectories(BASE.resolve("fixtures"));
        Path outside = Files.writeString(fixtures.resolve("outside-" + token + ".csv"),
                COURSE_HEADER + imported + ",2026/1,I\n", StandardCharsets.UTF_8);
        Path hardLinked = fixtures.resolve("hard-" + token + ".csv");
        Files.createLink(hardLinked, outside);

        IngestionDirectories.Snapshot snapshot;
        try {
            snapshot = directories.snapshot(hardLinked,
                    IngestionDirectories.newSnapshotName("hard-" + token + ".csv"), 64 * 1024);
        } catch (IngestionDirectories.NotARegularFileException e) {
            // File systems that report link counts reject multiply linked files outright.
            assertThat(hardLinked).exists();
            return;
        }
        Files.writeString(outside, appended + ",2026/1,I\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertThat(snapshot.oversized()).isFalse();
        assertThat(hardLinked).doesNotExist();
        String snapshotContent = Files.readString(snapshot.file(), StandardCharsets.UTF_8);
        assertThat(snapshotContent).contains(imported).doesNotContain(appended);
        assertThat(validator.validate(snapshot.file()).sha256())
                .isEqualTo(CsvPreLaunchValidator.sha256(snapshotContent.getBytes(StandardCharsets.UTF_8)));
        Files.delete(snapshot.file());
    }

    @Test
    void aBareCarriageReturnIsRejectedSoValidationAndImportAgreeOnRows() throws IOException {
        String token = token();

        JobLog log = importNow(staged("courses-cr-" + token + ".csv",
                COURSE_HEADER + courseName("cr-a") + ",1,2\r" + courseName("cr-b") + ",1,2\n"));

        assertThat(log.getStatus()).isEqualTo(JobLogStatus.FAILED);
        assertThat(log.getReason()).isEqualTo("INVALID_LINE_BREAK");
    }

    /**
     * Regression: at startup the recovery took {@code processing/.gitkeep} for an interrupted import and moved it to
     * {@code failed/} with a {@code .gitkeep.report.json}. Placeholders and desktop metadata in every folder, old
     * enough for every rule (stale snapshot, expired retention, stable inbox file), are left exactly as they are by
     * the startup recovery, the retention cleanup and the inbox poller, and no import is recorded for them. A
     * sidecar whose CSV is still retained is kept with it.
     */
    @Test
    void placeholdersAndForeignFilesAreNeverRecoveredReportedImportedOrDeleted() throws IOException {
        String token = token();
        Instant old = Instant.now().minus(Duration.ofDays(30));
        List<Path> foreign = new ArrayList<>();
        for (Path dir : List.of(directories.inbox(), directories.staging(), directories.processing(),
                directories.done(), directories.failed())) {
            for (String name : List.of(".gitkeep", ".DS_Store", "Thumbs.db")) {
                Path file = dir.resolve(name);
                if (!Files.exists(file)) {
                    Files.createFile(file);
                }
                Files.setLastModifiedTime(file, FileTime.from(old));
                foreign.add(file);
            }
        }
        // A hidden CSV in the inbox (a client's temporary name) and a foreign file in staging/ named like an upload.
        Path hiddenCsv = Files.writeString(directories.inbox().resolve(".hidden-" + token + ".csv"),
                COURSE_HEADER + courseName("hidden") + ",1,2\n", StandardCharsets.UTF_8);
        Path notAnUpload = Files.writeString(directories.staging().resolve("not-a-token__x-" + token + ".csv.staged"),
                "x", StandardCharsets.UTF_8);
        Path notASnapshot = Files.writeString(directories.processing().resolve("notes-" + token + ".csv"),
                "x", StandardCharsets.UTF_8);
        // A retained failed CSV with an expired sidecar: the sidecar goes only together with its CSV.
        String failedName = IngestionDirectories.newSnapshotName("kept-" + token + ".csv");
        Path keptCsv = Files.writeString(directories.failed().resolve(failedName), "x", StandardCharsets.UTF_8);
        Path keptReport = directories.writeReport(keptCsv, "{}".getBytes(StandardCharsets.UTF_8));
        for (Path file : List.of(hiddenCsv, notAnUpload, notASnapshot, keptReport)) {
            Files.setLastModifiedTime(file, FileTime.from(old));
        }
        Instant started = Instant.now();
        try {
            recovery.afterSingletonsInstantiated();
            recovery.periodicRecovery();
            retention.cleanup();
            // The poller keeps scanning the inbox: a real file dropped after the placeholders is imported, so at
            // least one full scan saw them all.
            String course = courseName("next");
            dropIntoInbox("courses-" + token + ".csv", (COURSE_HEADER + course + ",2026/1,I\n")
                    .getBytes(StandardCharsets.UTF_8));
            assertThat(awaitClosedJobLog(token, TIMEOUT).getStatus()).isEqualTo(JobLogStatus.SUCCEEDED);
            pause(Duration.ofMillis(600));

            for (Path file : foreign) {
                assertThat(file).as(file.toString()).exists().isEmptyFile();
                assertThat(Files.getLastModifiedTime(file).toInstant()).isBefore(started);
            }
            assertThat(List.of(hiddenCsv, notAnUpload, notASnapshot, keptCsv, keptReport)).allSatisfy(path ->
                    assertThat(path).exists());
            for (Path dir : List.of(directories.done(), directories.failed())) {
                assertThat(filesContaining(dir, ".gitkeep")).as("reports in " + dir).containsOnly(dir.resolve(".gitkeep"));
                assertThat(filesContaining(dir, "DS_Store")).containsOnly(dir.resolve(".DS_Store"));
                assertThat(filesContaining(dir, "Thumbs")).containsOnly(dir.resolve("Thumbs.db"));
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM job_log WHERE started_at >= ? AND (file_name LIKE "
                    + "'%gitkeep%' OR file_name LIKE '%DS_Store%' OR file_name LIKE '%Thumbs%' OR file_name = ?)",
                    Integer.class, Timestamp.from(started), FileNames.FALLBACK)).isZero();
            assertThat(jdbc.queryForList("SELECT file_name FROM job_log WHERE file_name LIKE ?", String.class,
                    "%" + token + "%")).containsExactly("courses-" + token + ".csv");
        } finally {
            for (Path file : List.of(hiddenCsv, notAnUpload, notASnapshot, keptCsv, keptReport)) {
                Files.deleteIfExists(file);
            }
            for (Path file : foreign) {
                Files.deleteIfExists(file);
            }
        }
    }

    private FileSystemPersistentAcceptOnceFileListFilter filterOfANewInstance() {
        JdbcMetadataStore store = new JdbcMetadataStore(dataSource);
        store.setRegion(IngestionFlowConfig.METADATA_REGION);
        store.afterPropertiesSet();
        return new FileSystemPersistentAcceptOnceFileListFilter(store, IngestionFlowConfig.ACCEPT_ONCE_PREFIX);
    }
}
