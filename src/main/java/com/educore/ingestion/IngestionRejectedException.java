package com.educore.ingestion;

import com.educore.common.web.ApiProblemException;

/**
 * A file failed pre-launch validation or idempotency. The poller records it in the job log and moves the file
 * to {@code failed/}; the manual upload endpoint answers with the problem {@code import/<reason>}.
 */
public class IngestionRejectedException extends ApiProblemException {

    private final IngestionReason reason;

    public IngestionRejectedException(IngestionReason reason) {
        super(reason.status(), reason.problemCode(), reason.title());
        this.reason = reason;
    }

    public IngestionReason reason() {
        return reason;
    }
}
