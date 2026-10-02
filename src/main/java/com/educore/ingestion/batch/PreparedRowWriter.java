package com.educore.ingestion.batch;

import com.educore.ingestion.IngestionFence;
import com.educore.ingestion.LeaseLostException;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.function.LongSupplier;

/**
 * Unwraps {@link PreparedRow}s and hands the entities to a {@link RepositoryItemWriter} ({@code save} per
 * entity). Keeping the wrapper as the writer's item type lets the skip listener report the line of a row
 * rejected by a database constraint. Database exceptions are replaced by {@link RowConstraintException} (or a
 * fixed {@code WRITE_FAILED} state), so neither SQL nor row values reach Batch's failure handling.
 * <p>
 * Before the first row of a chunk is written, the run's lease is verified and share-locked in the chunk
 * transaction ({@link IngestionFence}): a run that recovery closed, or whose lease expired, gets no further rows
 * ({@link LeaseLostException}, not skippable, fails the step).
 */
final class PreparedRowWriter<E> implements ItemWriter<PreparedRow<E>> {

    private static final Logger log = LoggerFactory.getLogger(PreparedRowWriter.class);

    private final RepositoryItemWriter<E> delegate;
    private final IngestionFence fence;
    private final LongSupplier jobLogId;

    /** The run is the {@code jobLogId} job parameter of the step executing on the current thread. */
    PreparedRowWriter(RepositoryItemWriter<E> delegate, IngestionFence fence) {
        this(delegate, fence, PreparedRowWriter::currentJobLogId);
    }

    PreparedRowWriter(RepositoryItemWriter<E> delegate, IngestionFence fence, LongSupplier jobLogId) {
        this.delegate = delegate;
        this.fence = fence;
        this.jobLogId = jobLogId;
    }

    @Override
    public void write(Chunk<? extends PreparedRow<E>> chunk) throws Exception {
        fence.verify(jobLogId.getAsLong());
        Chunk<E> entities = new Chunk<>();
        for (PreparedRow<E> row : chunk) {
            entities.add(row.entity());
        }
        try {
            delegate.write(entities);
        } catch (RowConstraintException e) {
            throw e;
        } catch (DataIntegrityViolationException e) {
            throw new RowConstraintException();
        } catch (RuntimeException e) {
            // Anything else fails the step; only the type is kept (messages can quote SQL and values).
            log.error("Import write failed error={}", e.getClass().getName());
            throw new IllegalStateException("WRITE_FAILED");
        }
    }

    /** Chunk threads of a step run with the step context registered (StepContextRepeatCallback). */
    private static long currentJobLogId() {
        StepContext context = StepSynchronizationManager.getContext();
        Long id = context == null ? null
                : context.getStepExecution().getJobExecution().getJobParameters().getLong("jobLogId");
        if (id == null) {
            throw new IllegalStateException("Import writer used outside an import step");
        }
        return id;
    }
}
