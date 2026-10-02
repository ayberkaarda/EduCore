package com.educore.ingestion;

import org.springframework.http.HttpStatus;

import java.util.Locale;

/**
 * File-level outcome codes stored in {@code job_log.reason}, written to the {@code .report.json} sidecar and,
 * for rejections of a manual upload, returned as the problem code {@code import/<kebab-case name>}.
 */
public enum IngestionReason {

    // Pre-launch rejections (the job never starts).
    INVALID_FILE_NAME(HttpStatus.BAD_REQUEST, "The file name is not a valid CSV file name."),
    NOT_A_REGULAR_FILE(HttpStatus.BAD_REQUEST, "The inbox entry is a link or not a regular file."),
    INVALID_LINE_BREAK(HttpStatus.BAD_REQUEST, "Lines must end with LF or CR LF; a bare CR is not allowed."),
    RECORD_TOO_LONG(HttpStatus.BAD_REQUEST, "A line exceeds the configured maximum length."),
    EMPTY_FILE(HttpStatus.BAD_REQUEST, "The file is empty."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "The file exceeds the configured size limit."),
    NOT_TEXT(HttpStatus.BAD_REQUEST, "The file is not a text file."),
    NOT_UTF8(HttpStatus.BAD_REQUEST, "The file is not valid UTF-8."),
    INVALID_HEADER(HttpStatus.BAD_REQUEST, "The header line does not match a supported CSV schema."),
    NO_DATA_ROWS(HttpStatus.BAD_REQUEST, "The file contains no data rows."),
    TOO_MANY_ROWS(HttpStatus.BAD_REQUEST, "The file exceeds the configured row limit."),
    DUPLICATE(HttpStatus.CONFLICT, "A file with the same content was already imported."),

    // Job outcomes.
    SKIP_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "Too many rows were rejected."),
    NO_ROWS_WRITTEN(HttpStatus.UNPROCESSABLE_ENTITY, "No row could be imported."),
    JOB_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "The import job failed."),
    INTERRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, "The import was interrupted by an application stop."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "The import failed unexpectedly.");

    private final HttpStatus status;
    private final String title;

    IngestionReason(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** {@code import/<kebab-case name>}, e.g. {@code import/invalid-header}. */
    public String problemCode() {
        return "import/" + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
