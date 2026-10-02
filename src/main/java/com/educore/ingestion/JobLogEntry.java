package com.educore.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One row-level (or file-level, {@code rowNumber == null}) outcome of an import ({@code job_log_entry}).
 * {@code reason} is a fixed code; {@code rawMasked} is the CSV line masked by {@link PiiMasker}.
 */
@Entity
@Table(name = "job_log_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobLogEntry {

    public enum Level { INFO, WARN, ERROR }

    static final int MAX_REASON = 64;
    static final int MAX_RAW = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long jobLogId;

    private Integer rowNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Level level;

    @Column(nullable = false, length = MAX_REASON)
    private String reason;

    @Column(length = MAX_RAW)
    private String rawMasked;

    JobLogEntry(long jobLogId, Integer rowNumber, Level level, String reason, String rawMasked) {
        this.jobLogId = jobLogId;
        this.rowNumber = rowNumber;
        this.level = level;
        this.reason = reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason;
        this.rawMasked = rawMasked == null || rawMasked.length() <= MAX_RAW ? rawMasked
                : rawMasked.substring(0, MAX_RAW);
    }
}
