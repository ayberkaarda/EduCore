package com.educore.ingestion;

import com.educore.entity.JobLog;
import com.educore.entity.JobLogStatus;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * One CSV import run as returned by the admin API. {@code status} is {@code null} while the run is in
 * progress; {@code successfulRecords} = rows written, {@code failedRecords} = rows skipped,
 * {@code readRecords} = data rows read (blank lines included).
 */
public record JobLogResponse(Long id, String fileName, String entityType, JobLogStatus status, String reason,
                             int readRecords, int successfulRecords, int failedRecords, Long importedFileId,
                             LocalDateTime createdAt, Instant startedAt, Instant finishedAt) {

    static JobLogResponse of(JobLog log) {
        return new JobLogResponse(log.getId(), log.getFileName(), log.getEntityType(), log.getStatus(),
                log.getReason(), log.getReadRecords(), log.getSuccessfulRecords(), log.getFailedRecords(),
                log.getImportedFileId(), log.getCreatedAt(), log.getStartedAt(), log.getFinishedAt());
    }
}
