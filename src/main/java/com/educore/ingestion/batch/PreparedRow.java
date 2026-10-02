package com.educore.ingestion.batch;

/** An entity built from a CSV row, carrying the row's line number and raw text for skip reporting. */
public record PreparedRow<E>(int lineNumber, String rawLine, E entity) {

    @Override
    public String toString() {
        return "PreparedRow[line=" + lineNumber + "]";
    }
}
