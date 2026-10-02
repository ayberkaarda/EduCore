package com.educore.ingestion;

/**
 * A manual upload accepted into the inbox: the file name it got there, its schema, data rows, size in bytes
 * and SHA-256.
 */
public record ImportAcceptedResponse(String inboxFileName, CsvKind kind, int rows, long size, String sha256) {
}
