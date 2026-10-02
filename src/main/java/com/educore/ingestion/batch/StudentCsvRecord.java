package com.educore.ingestion.batch;

import com.educore.common.validation.InputPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.batch.item.file.transform.FieldSet;

/**
 * One row of a student CSV ({@code FirstName,LastName,StudentNumber}). The rules match the admin API
 * (D-NEW-14): names start with a letter and contain letters, marks, space, {@code '}, {@code .} and
 * {@code -} (at most 100 characters); the student number has 4 to 12 digits. Values are trimmed.
 * {@code toString()} carries the line number only, so row values cannot reach logs.
 */
public record StudentCsvRecord(
        int lineNumber,
        String rawLine,
        boolean blank,
        @NotBlank @Size(max = 100) @Pattern(regexp = InputPatterns.PERSON_NAME) String firstName,
        @NotBlank @Size(max = 100) @Pattern(regexp = InputPatterns.PERSON_NAME) String lastName,
        @NotBlank @Pattern(regexp = InputPatterns.STUDENT_NUMBER) String studentNumber) implements CsvRow {

    static final String[] COLUMNS = {"firstName", "lastName", "studentNumber"};

    static StudentCsvRecord of(int lineNumber, String rawLine, FieldSet fields) {
        return new StudentCsvRecord(lineNumber, rawLine, false, fields.readString(0), fields.readString(1),
                fields.readString(2));
    }

    static StudentCsvRecord blankLine(int lineNumber, String rawLine) {
        return new StudentCsvRecord(lineNumber, rawLine, true, null, null, null);
    }

    @Override
    public String toString() {
        return "StudentCsvRecord[line=" + lineNumber + "]";
    }
}
