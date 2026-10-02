package com.educore.ingestion.batch;

import org.springframework.batch.item.ItemProcessor;

/** Filters blank lines (counted as filtered, not skipped: they do not make an import PARTIAL). */
final class BlankRowFilter<T extends CsvRow> implements ItemProcessor<T, T> {

    @Override
    public T process(T row) {
        return row.blank() ? null : row;
    }
}
