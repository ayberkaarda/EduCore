package com.educore.ingestion;

import com.educore.config.EduCoreProperties;
import com.educore.entity.JobLogStatus;
import com.educore.ingestion.batch.ImportJobConfig;
import com.educore.webhook.WebhookEvent;
import com.educore.webhook.WebhookPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.integration.file.filters.FileSystemPersistentAcceptOnceFileListFilter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Imports one file from the inbox, end to end:
 * <ol>
 *   <li>sanitise the name, open a job log owned by this instance (leased, see {@link IngestionInstance}) and
 *       copy the inbox file into the private snapshot {@code processing/<uuid>_<name>}
 *       ({@link IngestionDirectories#snapshot}; links and special files are rejected);</li>
 *   <li>open a job log, validate the file before launch ({@link CsvPreLaunchValidator}) and register its
 *       SHA-256 ({@code DUPLICATE} when the same content was already imported);</li>
 *   <li>run the batch job of the file's {@link CsvKind} synchronously;</li>
 *   <li>close the job log (SUCCEEDED / PARTIAL / FAILED, counts, reason; only while this instance still owns
 *       the run), then: SUCCEEDED and PARTIAL delete the snapshot when {@code retain-processed-days} is 0 (the
 *       default: the database keeps the hash, metadata, counts and masked rows) or move it to {@code done/} (PARTIAL
 *       with a {@code .report.json} sidecar); FAILED moves it to {@code failed/} with a report; finally
 *       {@code import.completed} or {@code import.failed} is published.</li>
 * </ol>
 * Files kept in {@code done/} and {@code failed/} are deleted by {@link IngestionRetention} after their retention
 * period, and lines of a purged student are removed from them at the purge ({@link IngestionRetention#scrub}).
 * Nothing thrown here reaches the poller: every failure ends as a FAILED job log.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final ObjectMapper REPORT_JSON = new ObjectMapper().findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final IngestionDirectories directories;
    private final CsvPreLaunchValidator validator;
    private final IngestionLedger ledger;
    private final JobLauncher jobLauncher;
    private final Map<CsvKind, Job> jobs;
    private final WebhookPublisher webhooks;
    private final IngestionInstance instance;
    private final FileSystemPersistentAcceptOnceFileListFilter acceptOnce;
    private final Clock clock;
    private final boolean deleteProcessed;

    public IngestionService(IngestionDirectories directories, CsvPreLaunchValidator validator, IngestionLedger ledger,
                            JobLauncher jobLauncher,
                            @Qualifier(ImportJobConfig.STUDENT_JOB) Job importStudentJob,
                            @Qualifier(ImportJobConfig.COURSE_JOB) Job importCourseJob,
                            WebhookPublisher webhooks, IngestionInstance instance,
                            FileSystemPersistentAcceptOnceFileListFilter ingestionAcceptOnceFilter, Clock clock,
                            EduCoreProperties properties) {
        this.instance = instance;
        this.acceptOnce = ingestionAcceptOnceFilter;
        this.directories = directories;
        this.validator = validator;
        this.ledger = ledger;
        this.jobLauncher = jobLauncher;
        this.jobs = Map.of(CsvKind.STUDENTS, importStudentJob, CsvKind.COURSES, importCourseJob);
        this.webhooks = webhooks;
        this.clock = clock;
        this.deleteProcessed = properties.ingestion().retainProcessedDays() == 0;
    }

    /**
     * Imports {@code inboxFile}; returns the job log id, or empty when the file could not be claimed (it then
     * stays in the inbox and its accept-once mark is released, so a later poll retries it).
     */
    public Optional<Long> ingest(Path inboxFile) {
        Optional<String> safeName = FileNames.sanitize(inboxFile.getFileName().toString());
        String name = safeName.orElse(FileNames.FALLBACK);
        String snapshotName = IngestionDirectories.newSnapshotName(name);
        Instant started = clock.instant();
        long jobLogId = ledger.open(name, started, instance.id(), instance.leaseUntil(), snapshotName);
        IngestionDirectories.Snapshot snapshot;
        try {
            snapshot = directories.snapshot(inboxFile, snapshotName, validator.maxBytes());
        } catch (IngestionDirectories.NotARegularFileException e) {
            return Optional.of(quarantine(jobLogId, inboxFile, snapshotName));
        } catch (IOException e) {
            // Still being written, locked by another process or unreadable: try again on a later poll.
            ledger.discard(jobLogId);
            acceptOnce.remove(inboxFile.toFile());
            log.warn("Inbox file could not be claimed; it stays in the inbox error={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        log.info("Import started jobLogId={}", jobLogId);

        ImportOutcome outcome;
        try {
            outcome = snapshot.oversized() ? ImportOutcome.rejected(IngestionReason.FILE_TOO_LARGE, null)
                    : run(jobLogId, snapshot.file(), safeName.isPresent(), name, started);
        } catch (RuntimeException e) {
            log.error("Import failed unexpectedly jobLogId={} error={}", jobLogId, e.getClass().getName());
            outcome = ImportOutcome.rejected(IngestionReason.INTERNAL_ERROR, null);
        }
        complete(jobLogId, snapshot.file(), outcome);
        return Optional.of(jobLogId);
    }

    /** A link or special entry: moved (not followed) to {@code failed/} and recorded as FAILED. */
    private long quarantine(long jobLogId, Path inboxFile, String snapshotName) {
        try {
            directories.quarantine(inboxFile, snapshotName);
        } catch (IOException e) {
            acceptOnce.remove(inboxFile.toFile());
            log.warn("Rejected inbox entry could not be moved error={}", e.getClass().getSimpleName());
        }
        ImportOutcome outcome = ImportOutcome.rejected(IngestionReason.NOT_A_REGULAR_FILE, null);
        if (!ledger.close(jobLogId, outcome, clock.instant(), instance.id())) {
            log.warn("Rejected import run was already closed by recovery jobLogId={}", jobLogId);
            return jobLogId;
        }
        log.warn("Import rejected jobLogId={} reason={}", jobLogId, outcome.reason());
        publish(jobLogId, outcome, null);
        return jobLogId;
    }

    private ImportOutcome run(long jobLogId, Path processing, boolean nameValid, String name, Instant started) {
        CsvKind kind = null;
        long importedFileId;
        try {
            if (!nameValid) {
                throw new IngestionRejectedException(IngestionReason.INVALID_FILE_NAME);
            }
            ValidatedCsv csv = validator.validate(processing);
            kind = csv.kind();
            importedFileId = claim(jobLogId, csv, name, started);
        } catch (IngestionRejectedException rejected) {
            return ImportOutcome.rejected(rejected.reason(), kind);
        }
        JobParameters parameters = new JobParametersBuilder()
                .addLong("jobLogId", jobLogId)
                .addLong("importedFileId", importedFileId)
                .addString("runId", UUID.randomUUID().toString())
                .toJobParameters();
        JobExecution execution;
        try {
            execution = jobLauncher.run(jobs.get(kind), parameters);
        } catch (Exception e) {
            log.error("Import job could not be launched jobLogId={} error={}", jobLogId, e.getClass().getName());
            return ImportOutcome.rejected(IngestionReason.JOB_FAILED, kind);
        }
        int read = 0;
        int written = 0;
        int skipped = 0;
        for (StepExecution step : execution.getStepExecutions()) {
            read += (int) (step.getReadCount() + step.getReadSkipCount());
            written += (int) step.getWriteCount();
            skipped += (int) step.getSkipCount();
        }
        boolean completed = execution.getStatus() == BatchStatus.COMPLETED;
        if (!completed) {
            log.warn("Import job did not complete jobLogId={} status={} exitCode={}", jobLogId, execution.getStatus(),
                    execution.getExitStatus().getExitCode());
        }
        return ImportOutcome.of(completed, skipLimitExceeded(execution), kind, read, written, skipped,
                execution.getId());
    }

    private long claim(long jobLogId, ValidatedCsv csv, String name, Instant now) {
        try {
            return ledger.claimFile(jobLogId, csv, name, now);
        } catch (DataIntegrityViolationException concurrentClaim) {
            // Another import registered the same content between the lookup and the insert.
            throw new IngestionRejectedException(IngestionReason.DUPLICATE);
        }
    }

    private static boolean skipLimitExceeded(JobExecution execution) {
        for (Throwable failure : execution.getAllFailureExceptions()) {
            for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
                if (t instanceof SkipLimitExceededException) {
                    return true;
                }
            }
        }
        return false;
    }

    private void complete(long jobLogId, Path snapshot, ImportOutcome outcome) {
        Instant finished = clock.instant();
        if (!ledger.close(jobLogId, outcome, finished, instance.id())) {
            log.warn("Import run was already closed by recovery jobLogId={}", jobLogId);
            return;
        }
        Long importedFileId = ledger.importedFileId(jobLogId);
        boolean succeeded = outcome.status() != JobLogStatus.FAILED;
        try {
            if (succeeded && deleteProcessed) {
                // Erasure by default (AC-08): the imported rows live in the database, the file is not kept.
                Files.deleteIfExists(snapshot);
            } else {
                Path finalFile = directories.finish(snapshot, succeeded);
                if (outcome.status() != JobLogStatus.SUCCEEDED) {
                    directories.writeReport(finalFile, report(jobLogId, importedFileId, outcome));
                }
            }
        } catch (IOException e) {
            log.error("Imported file could not be moved out of processing jobLogId={} error={}", jobLogId,
                    e.getClass().getName());
        }
        log.info("Import finished jobLogId={} status={} reason={} read={} written={} skipped={}", jobLogId,
                outcome.status(), outcome.reason(), outcome.read(), outcome.written(), outcome.skipped());
        publish(jobLogId, outcome, importedFileId);
    }

    private byte[] report(long jobLogId, Long importedFileId, ImportOutcome outcome) throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("jobLogId", jobLogId);
        report.put("importedFileId", importedFileId);
        report.put("kind", outcome.kind());
        report.put("status", outcome.status());
        report.put("reason", outcome.reason());
        report.put("rows", Map.of("read", outcome.read(), "written", outcome.written(), "skipped", outcome.skipped()));
        List<Map<String, Object>> entries = ledger.entries(jobLogId).stream().map(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("row", entry.getRowNumber());
            row.put("level", entry.getLevel());
            row.put("reason", entry.getReason());
            row.put("raw", entry.getRawMasked());
            return row;
        }).toList();
        report.put("entries", entries);
        return REPORT_JSON.writeValueAsBytes(report);
    }

    /** Webhook data: opaque ids, kind, status, reason and counts only (no file name: it may contain names). */
    private void publish(long jobLogId, ImportOutcome outcome, Long importedFileId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobLogId", jobLogId);
        data.put("importedFileId", importedFileId);
        data.put("kind", outcome.kind() == null ? null : outcome.kind().name());
        data.put("status", outcome.status().name());
        data.put("reason", outcome.reason() == null ? null : outcome.reason().name());
        data.put("rows", Map.of("read", outcome.read(), "written", outcome.written(), "skipped", outcome.skipped()));
        try {
            webhooks.publish(outcome.status() == JobLogStatus.FAILED ? WebhookEvent.IMPORT_FAILED
                    : WebhookEvent.IMPORT_COMPLETED, data);
        } catch (RuntimeException e) {
            log.error("Import webhook event could not be queued jobLogId={} error={}", jobLogId, e.getClass().getName());
        }
    }
}
