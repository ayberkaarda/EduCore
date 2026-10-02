package com.educore.entity;

/**
 * Outcome of a CSV import ({@code job_log.status}).
 * <ul>
 *   <li>{@code SUCCEEDED}: every data row was written.</li>
 *   <li>{@code PARTIAL}: at least one row was written and at least one was skipped; the file goes to
 *       {@code done/} with a {@code .report.json} sidecar.</li>
 *   <li>{@code FAILED}: the file was rejected, the job failed, or no row was written; the file goes to
 *       {@code failed/}.</li>
 * </ul>
 */
public enum JobLogStatus {
    SUCCEEDED,
    PARTIAL,
    FAILED
}
