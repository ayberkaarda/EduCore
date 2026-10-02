package com.educore.ingestion.batch;

import com.educore.ingestion.IngestionLedger;
import com.educore.ingestion.JobLogEntry;
import com.educore.ingestion.PiiMasker;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.transform.IncorrectTokenCountException;
import org.springframework.batch.item.validator.ValidationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Records every skipped row as a {@code WARN} job log entry: physical line number, the raw line masked by
 * {@link PiiMasker}, and a fixed reason code (never an exception message):
 * <ul>
 *   <li>{@code WRONG_COLUMN_COUNT}, {@code PARSE_ERROR}: the line could not be tokenised;</li>
 *   <li>{@code INVALID_FIELD:<fields>}: Bean Validation failed for the named columns;</li>
 *   <li>{@code DUPLICATE_IN_FILE}, {@code ALREADY_EXISTS}: see {@link DuplicateRowException};</li>
 *   <li>{@code CONSTRAINT_VIOLATION}: the database rejected the row.</li>
 * </ul>
 * Batch calls these methods inside the chunk transaction, so an entry is committed with its chunk.
 */
public class RowSkipRecorder implements SkipListener<CsvRow, PreparedRow<?>> {

    private final IngestionLedger ledger;
    private final long jobLogId;

    public RowSkipRecorder(IngestionLedger ledger, long jobLogId) {
        this.ledger = ledger;
        this.jobLogId = jobLogId;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        if (t instanceof RowParseException parse) {
            ledger.entry(jobLogId, parse.getLineNumber(), JobLogEntry.Level.WARN, parse.code(), parse.getInput());
        } else if (t instanceof FlatFileParseException parse) {
            String reason = parse.getCause() instanceof IncorrectTokenCountException ? "WRONG_COLUMN_COUNT" : "PARSE_ERROR";
            record(parse.getLineNumber(), parse.getInput(), reason);
        } else {
            record(null, null, "PARSE_ERROR");
        }
    }

    @Override
    public void onSkipInProcess(CsvRow item, Throwable t) {
        record(item.lineNumber(), item.rawLine(), reasonFor(t));
    }

    @Override
    public void onSkipInWrite(PreparedRow<?> item, Throwable t) {
        record(item.lineNumber(), item.rawLine(), reasonFor(t));
    }

    static String reasonFor(Throwable t) {
        if (t instanceof DuplicateRowException duplicate) {
            return duplicate.code();
        }
        if (t instanceof InvalidRowException invalid) {
            return invalid.code();
        }
        if (t instanceof ValidationException validation) {
            return InvalidRowException.of(validation).code();
        }
        if (t instanceof DataIntegrityViolationException) {
            return "CONSTRAINT_VIOLATION";
        }
        return "SKIPPED";
    }

    private void record(Integer lineNumber, String raw, String reason) {
        ledger.entry(jobLogId, lineNumber, JobLogEntry.Level.WARN, reason, PiiMasker.mask(raw));
    }
}
