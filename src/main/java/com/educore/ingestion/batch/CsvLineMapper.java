package com.educore.ingestion.batch;

import org.springframework.batch.item.file.LineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.file.transform.FieldSet;

/**
 * Maps one CSV record to a {@link CsvRow} with a strict {@link DelimitedLineTokenizer} (comma, double-quote
 * quoting, {@code ""} for a quote inside a quoted field, quoted delimiters and line breaks kept; a wrong column
 * count raises {@code IncorrectTokenCountException}). The reader wraps every mapping failure into a
 * {@code FlatFileParseException} carrying the line number and the raw input.
 */
final class CsvLineMapper<T extends CsvRow> implements LineMapper<T> {

    interface RowFactory<T> {
        T create(int lineNumber, String rawLine, FieldSet fields);
    }

    interface BlankFactory<T> {
        T create(int lineNumber, String rawLine);
    }

    private final DelimitedLineTokenizer tokenizer;
    private final RowFactory<T> rows;
    private final BlankFactory<T> blanks;

    CsvLineMapper(String[] columns, RowFactory<T> rows, BlankFactory<T> blanks) {
        this.tokenizer = new DelimitedLineTokenizer(DelimitedLineTokenizer.DELIMITER_COMMA);
        this.tokenizer.setQuoteCharacter(DelimitedLineTokenizer.DEFAULT_QUOTE_CHARACTER);
        this.tokenizer.setNames(columns);
        this.tokenizer.setStrict(true);
        this.rows = rows;
        this.blanks = blanks;
    }

    @Override
    public T mapLine(String line, int lineNumber) {
        if (line.isBlank()) {
            return blanks.create(lineNumber, line);
        }
        return rows.create(lineNumber, line, tokenizer.tokenize(line));
    }
}
