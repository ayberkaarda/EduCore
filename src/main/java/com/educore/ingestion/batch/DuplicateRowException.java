package com.educore.ingestion.batch;

import org.springframework.batch.item.validator.ValidationException;

/**
 * A row whose key (student number, course name) already appeared earlier in the file
 * ({@code DUPLICATE_IN_FILE}) or already exists in the database ({@code ALREADY_EXISTS}). A
 * {@link ValidationException}, so the step skips it like any other invalid row. The message is the code only.
 */
public class DuplicateRowException extends ValidationException {

    public static final String IN_FILE = "DUPLICATE_IN_FILE";
    public static final String IN_DATABASE = "ALREADY_EXISTS";

    private final String code;

    DuplicateRowException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
