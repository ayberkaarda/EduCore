package com.educore.ingestion;

import com.educore.entity.JobLogStatus;

/**
 * Result of one import: {@code PARTIAL} when rows were skipped but at least one was written; {@code FAILED}
 * when the file was rejected, the job failed or nothing was written; {@code SUCCEEDED} otherwise.
 * {@code reason} is {@code null} for SUCCEEDED and PARTIAL.
 */
public record ImportOutcome(JobLogStatus status, IngestionReason reason, CsvKind kind, int read, int written,
                            int skipped, Long jobExecutionId) {

    static ImportOutcome rejected(IngestionReason reason, CsvKind kind) {
        return new ImportOutcome(JobLogStatus.FAILED, reason, kind, 0, 0, 0, null);
    }

    /** Applies the outcome rules to the counts of a completed or failed job execution. */
    static ImportOutcome of(boolean jobCompleted, boolean skipLimitExceeded, CsvKind kind, int read, int written,
                            int skipped, Long jobExecutionId) {
        if (!jobCompleted) {
            IngestionReason reason = skipLimitExceeded ? IngestionReason.SKIP_LIMIT_EXCEEDED : IngestionReason.JOB_FAILED;
            return new ImportOutcome(JobLogStatus.FAILED, reason, kind, read, written, skipped, jobExecutionId);
        }
        if (written == 0) {
            return new ImportOutcome(JobLogStatus.FAILED, IngestionReason.NO_ROWS_WRITTEN, kind, read, written,
                    skipped, jobExecutionId);
        }
        JobLogStatus status = skipped > 0 ? JobLogStatus.PARTIAL : JobLogStatus.SUCCEEDED;
        return new ImportOutcome(status, null, kind, read, written, skipped, jobExecutionId);
    }
}
