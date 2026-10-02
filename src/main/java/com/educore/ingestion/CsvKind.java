package com.educore.ingestion;

import java.util.Arrays;
import java.util.Optional;

/**
 * The CSV schemas the pipeline accepts. A file's kind is decided by its header line, which must equal one of
 * the declared headers exactly (same names, order, case and delimiter; a UTF-8 BOM before it is tolerated).
 */
public enum CsvKind {

    STUDENTS("FirstName,LastName,StudentNumber"),
    COURSES("name,term,instructor");

    private final String header;

    CsvKind(String header) {
        this.header = header;
    }

    public String header() {
        return header;
    }

    /** Number of columns every data row must have. */
    public int columns() {
        return header.split(",").length;
    }

    public static Optional<CsvKind> forHeader(String headerLine) {
        return Arrays.stream(values()).filter(kind -> kind.header.equals(headerLine)).findFirst();
    }
}
