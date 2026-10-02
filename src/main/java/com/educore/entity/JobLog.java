package com.educore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * One CSV import run. {@code status} is {@code null} while the job runs and one of
 * {@link JobLogStatus} afterwards; {@code successfulRecords} counts written rows, {@code failedRecords}
 * skipped rows. Row-level details are {@code job_log_entry} rows ({@code com.educore.ingestion.JobLogEntry}).
 */
@Entity
@Table(name = "job_log")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileName;

    /** {@code STUDENTS} or {@code COURSES}; {@code null} when the file was rejected before its kind was known. */
    private String entityType;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private JobLogStatus status;

    /** File-level outcome code (e.g. {@code DUPLICATE}, {@code INVALID_HEADER}); {@code null} for a clean run. */
    @Column(length = 64)
    private String reason;

    @Column(nullable = false)
    private int readRecords;

    @Column(nullable = false)
    private int successfulRecords;

    @Column(nullable = false)
    private int failedRecords;

    private Long importedFileId;

    private LocalDateTime createdAt;

    private Instant startedAt;

    private Instant finishedAt;

    /** Instance that runs the import while {@code status} is {@code null}. */
    @Column(length = 64)
    private String owner;

    /** Renewed by the owner while it works; an open run with an expired lease is recovered. */
    private Instant leaseUntil;

    /** File name of the private snapshot in {@code processing/} (and later {@code done/} or {@code failed/}). */
    private String snapshotName;
}
