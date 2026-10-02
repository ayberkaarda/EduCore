package com.educore.ingestion;

import com.educore.entity.JobLog;
import com.educore.entity.JobLogStatus;
import com.educore.repository.JobLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Database side of an import run: the {@code job_log} row, its {@code job_log_entry} rows and the
 * {@code imported_file} idempotency record. Every method is one short transaction (entries written from a
 * batch skip listener join the chunk transaction instead).
 * <p>
 * Fencing (AC-17): every state transition of an open run is a conditional update. The owner closes its run only
 * while it is still open and still its own ({@link #close}); recovery closes a run only while its lease is
 * expired ({@link #failExpired}); a heartbeat extends only leases that have not expired yet. Both closes first take
 * the run's fence lock exclusively and chunk writes take it shared before they check the run
 * ({@link IngestionFence}), so a close waits for the chunks in flight and every later chunk of the closed run is
 * rejected.
 */
@Service
public class IngestionLedger {

    private static final String CLOSE_AS_OWNER = "UPDATE job_log SET status = ?, reason = ?, read_records = ?, "
            + "successful_records = ?, failed_records = ?, finished_at = ? "
            + "WHERE id = ? AND status IS NULL AND owner = ?";
    private static final String CLOSE_IF_EXPIRED = "UPDATE job_log SET status = ?, reason = ?, read_records = ?, "
            + "successful_records = ?, failed_records = ?, finished_at = ? "
            + "WHERE id = ? AND status IS NULL AND (lease_until IS NULL OR lease_until < ?)";
    private static final String RENEW = "UPDATE job_log SET lease_until = ? "
            + "WHERE status IS NULL AND owner = ? AND lease_until >= ?";
    private static final String IMPORTED_FILE_OF = "SELECT imported_file_id FROM job_log WHERE id = ?";

    private final JobLogRepository jobLogRepository;
    private final JobLogEntryRepository entryRepository;
    private final ImportedFileRepository importedFileRepository;
    private final JdbcTemplate jdbc;

    public IngestionLedger(JobLogRepository jobLogRepository, JobLogEntryRepository entryRepository,
                           ImportedFileRepository importedFileRepository, JdbcTemplate jdbc) {
        this.jobLogRepository = jobLogRepository;
        this.entryRepository = entryRepository;
        this.importedFileRepository = importedFileRepository;
        this.jdbc = jdbc;
    }

    /**
     * Opens a run owned by {@code owner} until {@code leaseUntil}: status stays {@code null} until
     * {@link #close}. {@code snapshotName} is reserved before the snapshot file exists, so recovery never
     * mistakes a snapshot being written for an abandoned one.
     */
    @Transactional
    public long open(String fileName, Instant startedAt, String owner, Instant leaseUntil, String snapshotName) {
        JobLog log = JobLog.builder()
                .fileName(fileName)
                .createdAt(LocalDateTime.ofInstant(startedAt, ZoneId.systemDefault()))
                .startedAt(startedAt)
                .owner(owner)
                .leaseUntil(leaseUntil)
                .snapshotName(snapshotName)
                .build();
        return jobLogRepository.save(log).getId();
    }

    /** Removes a run that never started (its file could not be claimed and stays in the inbox). */
    @Transactional
    public void discard(long jobLogId) {
        jobLogRepository.deleteById(jobLogId);
    }

    @Transactional(readOnly = true)
    public String snapshotName(long jobLogId) {
        return jobLogRepository.findById(jobLogId).map(JobLog::getSnapshotName).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Long importedFileId(long jobLogId) {
        return jobLogRepository.findById(jobLogId).map(JobLog::getImportedFileId).orElse(null);
    }

    /**
     * Heartbeat: extends the leases of the runs {@code owner} has open. A lease that already expired is not
     * revived: recovery may be closing that run, and its chunk writes are rejected from then on.
     */
    @Transactional
    public int renewLeases(String owner, Instant now, Instant until) {
        return jdbc.update(RENEW, Timestamp.from(until), owner, Timestamp.from(now));
    }

    /**
     * Registers the file for import. A file with the same SHA-256 that is not FAILED makes this a
     * {@code DUPLICATE}; a FAILED one is reused for the new attempt.
     *
     * @throws IngestionRejectedException with {@link IngestionReason#DUPLICATE}
     */
    @Transactional
    public long claimFile(long jobLogId, ValidatedCsv csv, String originalName, Instant now) {
        Optional<ImportedFile> existing = importedFileRepository.findBySha256ForUpdate(csv.sha256());
        ImportedFile file;
        if (existing.isPresent()) {
            file = existing.get();
            if (file.getStatus() != ImportedFile.Status.FAILED) {
                throw new IngestionRejectedException(IngestionReason.DUPLICATE);
            }
            file.restart(csv, originalName, now);
        } else {
            file = importedFileRepository.saveAndFlush(new ImportedFile(csv, originalName, now));
        }
        JobLog log = jobLogRepository.findById(jobLogId).orElseThrow();
        log.setImportedFileId(file.getId());
        log.setEntityType(csv.kind().name());
        return file.getId();
    }

    /** Adds an entry; joins the caller's transaction (the chunk transaction for row skips). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void entry(long jobLogId, Integer rowNumber, JobLogEntry.Level level, String reason, String rawMasked) {
        entryRepository.save(new JobLogEntry(jobLogId, rowNumber, level, reason, rawMasked));
    }

    /**
     * Closes the run of {@code owner} and its imported file (when there is one) with the final outcome. Only an
     * open run of that owner is closed: a run that recovery already closed (its lease expired) stays as it is.
     *
     * @return {@code false} when the run was already closed or belongs to another owner
     */
    @Transactional
    public boolean close(long jobLogId, ImportOutcome outcome, Instant finishedAt, String owner) {
        IngestionFence.lockForClose(jdbc, jobLogId);
        int updated = jdbc.update(CLOSE_AS_OWNER, outcome.status().name(), reasonOf(outcome), outcome.read(),
                outcome.written(), outcome.skipped(), Timestamp.from(finishedAt), jobLogId, owner);
        if (updated == 0) {
            return false;
        }
        afterClose(jobLogId, outcome);
        return true;
    }

    private boolean closeIfExpired(long jobLogId, ImportOutcome outcome, Instant now) {
        IngestionFence.lockForClose(jdbc, jobLogId);
        int updated = jdbc.update(CLOSE_IF_EXPIRED, outcome.status().name(), reasonOf(outcome), outcome.read(),
                outcome.written(), outcome.skipped(), Timestamp.from(now), jobLogId, Timestamp.from(now));
        if (updated == 0) {
            return false;
        }
        afterClose(jobLogId, outcome);
        return true;
    }

    private static String reasonOf(ImportOutcome outcome) {
        return outcome.reason() == null ? null : outcome.reason().name();
    }

    private void afterClose(long jobLogId, ImportOutcome outcome) {
        if (outcome.reason() != null) {
            entryRepository.save(new JobLogEntry(jobLogId, null, outcome.status() == JobLogStatus.FAILED
                    ? JobLogEntry.Level.ERROR : JobLogEntry.Level.WARN, outcome.reason().name(), null));
        }
        Long importedFileId = jdbc.queryForObject(IMPORTED_FILE_OF, Long.class, jobLogId);
        if (importedFileId != null) {
            importedFileRepository.findById(importedFileId).ifPresent(file -> file.finish(
                    ImportedFile.Status.valueOf(outcome.status().name()), outcome.jobExecutionId()));
        }
    }

    @Transactional(readOnly = true)
    public List<JobLogEntry> entries(long jobLogId) {
        return entryRepository.findTop1000ByJobLogIdOrderByIdAsc(jobLogId);
    }

    /**
     * Closes open runs whose lease expired (their owner stopped or crashed) as FAILED / {@code INTERRUPTED}
     * and fails PROCESSING files without an open run. Live runs of any instance are left alone.
     *
     * @return the snapshot names of the runs closed
     */
    @Transactional
    public List<String> failExpired(Instant now) {
        List<String> snapshots = new ArrayList<>();
        for (JobLog run : jobLogRepository.findExpiredOpenRuns(now)) {
            // Re-checked by the conditional update: a run whose owner renewed it meanwhile stays open.
            boolean closed = closeIfExpired(run.getId(), ImportOutcome.rejected(IngestionReason.INTERRUPTED, null),
                    now);
            if (closed && run.getSnapshotName() != null) {
                snapshots.add(run.getSnapshotName());
            }
        }
        importedFileRepository.failOrphans();
        return snapshots;
    }

    /** Snapshot names that belong to open runs with a valid lease. */
    @Transactional(readOnly = true)
    public Set<String> liveSnapshots(Instant now) {
        return new HashSet<>(jobLogRepository.findLiveSnapshotNames(now));
    }
}
