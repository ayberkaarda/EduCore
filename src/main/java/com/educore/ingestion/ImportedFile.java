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

import java.time.Instant;

/** A CSV file accepted for import, identified by the SHA-256 of its bytes ({@code imported_file}). */
@Entity
@Table(name = "imported_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImportedFile {

    public enum Status { PROCESSING, SUCCEEDED, PARTIAL, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String sha256;

    @Column(nullable = false)
    private String originalName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CsvKind kind;

    @Column(nullable = false)
    private long size;

    @Column(nullable = false)
    private int rows;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    private Long jobExecutionId;

    @Column(nullable = false)
    private Instant receivedAt;

    ImportedFile(ValidatedCsv csv, String originalName, Instant receivedAt) {
        this.sha256 = csv.sha256();
        restart(csv, originalName, receivedAt);
    }

    /** Reuses the row of an earlier FAILED import of the same content for a new attempt. */
    void restart(ValidatedCsv csv, String originalName, Instant receivedAt) {
        this.originalName = originalName;
        this.kind = csv.kind();
        this.size = csv.size();
        this.rows = csv.rows();
        this.status = Status.PROCESSING;
        this.jobExecutionId = null;
        this.receivedAt = receivedAt;
    }

    void finish(Status status, Long jobExecutionId) {
        this.status = status;
        this.jobExecutionId = jobExecutionId;
    }
}
