package com.educore.ingestion.batch;

/**
 * A data row read from a CSV file: its physical line number in the file (the header is line 1) and the raw
 * record text (only ever stored masked). A blank line is read as a row with {@code blank() == true} and
 * filtered out by {@link BlankRowFilter}.
 */
public interface CsvRow {

    int lineNumber();

    String rawLine();

    boolean blank();
}
