package com.educore.ingestion.batch;

import com.educore.ingestion.PiiMasker;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.transform.IncorrectTokenCountException;

/**
 * Replaces the reader's {@link FlatFileParseException} (whose message, input and cause quote the raw CSV
 * line) before Spring Batch sees it: the message is a fixed code with the line number, {@code getInput()} is
 * the line masked by {@link PiiMasker}, and there is no cause. Still a {@link FlatFileParseException}, so the
 * step skips it like the original.
 */
public class RowParseException extends FlatFileParseException {

    private final String code;

    RowParseException(String code, String maskedInput, int lineNumber) {
        super(code + " at line " + lineNumber, maskedInput, lineNumber);
        this.code = code;
    }

    static RowParseException of(FlatFileParseException e) {
        String code = e.getCause() instanceof IncorrectTokenCountException ? "WRONG_COLUMN_COUNT" : "PARSE_ERROR";
        return new RowParseException(code, PiiMasker.mask(e.getInput()), e.getLineNumber());
    }

    public String code() {
        return code;
    }
}
