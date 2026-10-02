package com.educore.ingestion;

/**
 * One job log entry: the physical line in the file ({@code null} for a file-level entry), level, fixed
 * reason code and the masked raw line.
 */
public record JobLogEntryResponse(Long id, Integer rowNumber, JobLogEntry.Level level, String reason,
                                  String rawMasked) {

    static JobLogEntryResponse of(JobLogEntry entry) {
        return new JobLogEntryResponse(entry.getId(), entry.getRowNumber(), entry.getLevel(), entry.getReason(),
                entry.getRawMasked());
    }
}
