package com.educore.ingestion.batch;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Replaces a {@link DataIntegrityViolationException} from the writer, whose message and cause carry the SQL
 * and the rejected values, by the fixed code {@code CONSTRAINT_VIOLATION} without a cause. Still a
 * {@link DataIntegrityViolationException}, so the step skips the row like the original.
 */
public class RowConstraintException extends DataIntegrityViolationException {

    public static final String CODE = "CONSTRAINT_VIOLATION";

    RowConstraintException() {
        super(CODE);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
