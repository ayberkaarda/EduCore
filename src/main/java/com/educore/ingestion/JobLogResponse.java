package com.educore.ingestion;

import com.educore.entity.JobLog;

import java.time.LocalDateTime;

/** One CSV import run as returned by the admin API. */
public record JobLogResponse(Long id, String fileName, String entityType, String status, int successfulRecords,
                             int failedRecords, LocalDateTime createdAt, String detailedLogs) {

    static JobLogResponse of(JobLog log) {
        return new JobLogResponse(log.getId(), log.getFileName(), log.getEntityType(), log.getStatus(),
                log.getSuccessfulRecords(), log.getFailedRecords(), log.getCreatedAt(), log.getDetailedLogs());
    }
}
