package com.educore.ingestion;

/** A file that passed pre-launch validation: its schema, SHA-256 (hex of the raw bytes), size and data rows. */
public record ValidatedCsv(CsvKind kind, String sha256, long size, int rows) {
}
