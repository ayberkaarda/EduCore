package com.educore.ingestion;

/**
 * The import run lost its lease (closed by recovery, owned by another instance, or expired) while it was still
 * writing. Not skippable: the chunk rolls back and the step fails. The message carries the run id only.
 */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(long jobLogId) {
        super("Import run lost its lease jobLogId=" + jobLogId);
    }
}
