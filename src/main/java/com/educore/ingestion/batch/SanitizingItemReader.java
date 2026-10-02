package com.educore.ingestion.batch;

import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;

/**
 * The step's reader: a {@link SynchronizedItemStreamReader} (thread-safe for the multi-threaded step) whose
 * parse failures are replaced by {@link RowParseException}s, so no raw CSV text reaches Batch's failure
 * handling, exit descriptions or logs.
 */
public class SanitizingItemReader<T> implements ItemStreamReader<T> {

    private final SynchronizedItemStreamReader<T> delegate;

    public SanitizingItemReader(SynchronizedItemStreamReader<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public T read() throws Exception {
        try {
            return delegate.read();
        } catch (RowParseException e) {
            throw e;
        } catch (FlatFileParseException e) {
            throw RowParseException.of(e);
        }
    }

    @Override
    public void open(ExecutionContext executionContext) {
        delegate.open(executionContext);
    }

    @Override
    public void update(ExecutionContext executionContext) {
        delegate.update(executionContext);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
