package com.educore.ingestion.batch;

import com.educore.common.validation.InputPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.batch.item.file.transform.FieldSet;

/**
 * One row of a course CSV ({@code name,term,instructor}). The rules match the admin API (D-NEW-14): name
 * required, at most 150 characters; term at most 50; instructor at most 100; no control characters.
 * {@code toString()} carries the line number only.
 */
public record CourseCsvRecord(
        int lineNumber,
        String rawLine,
        boolean blank,
        @NotBlank @Size(max = 150) @Pattern(regexp = InputPatterns.SINGLE_LINE_TEXT) String name,
        @Size(max = 50) @Pattern(regexp = InputPatterns.SINGLE_LINE_TEXT) String term,
        @Size(max = 100) @Pattern(regexp = InputPatterns.SINGLE_LINE_TEXT) String instructor) implements CsvRow {

    static final String[] COLUMNS = {"name", "term", "instructor"};

    static CourseCsvRecord of(int lineNumber, String rawLine, FieldSet fields) {
        return new CourseCsvRecord(lineNumber, rawLine, false, fields.readString(0), emptyToNull(fields.readString(1)),
                emptyToNull(fields.readString(2)));
    }

    static CourseCsvRecord blankLine(int lineNumber, String rawLine) {
        return new CourseCsvRecord(lineNumber, rawLine, true, null, null, null);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    @Override
    public String toString() {
        return "CourseCsvRecord[line=" + lineNumber + "]";
    }
}
