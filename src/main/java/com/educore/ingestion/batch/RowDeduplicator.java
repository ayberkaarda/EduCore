package com.educore.ingestion.batch;

import org.springframework.batch.item.ItemProcessor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Rejects a row whose key was already claimed by another line of the same file, or that already exists in the
 * database. One instance per step execution (step scope); thread-safe for the multi-threaded step.
 * <p>
 * A key is claimed by the first line that reaches this processor; processing the same line again (after a
 * chunk rollback the step re-processes its items) finds its own claim and passes, so retries never turn a
 * row into its own duplicate. With several threads "first" means first processed, not lowest line number.
 */
final class RowDeduplicator<T extends CsvRow> implements ItemProcessor<T, T> {

    private final Map<String, Integer> claims = new ConcurrentHashMap<>();
    private final Function<T, String> key;
    private final Predicate<String> existsInDatabase;

    RowDeduplicator(Function<T, String> key, Predicate<String> existsInDatabase) {
        this.key = key;
        this.existsInDatabase = existsInDatabase;
    }

    @Override
    public T process(T row) {
        String value = key.apply(row);
        Integer claimedBy = claims.putIfAbsent(value, row.lineNumber());
        if (claimedBy != null && claimedBy != row.lineNumber()) {
            throw new DuplicateRowException(DuplicateRowException.IN_FILE);
        }
        if (existsInDatabase.test(value)) {
            throw new DuplicateRowException(DuplicateRowException.IN_DATABASE);
        }
        return row;
    }
}
