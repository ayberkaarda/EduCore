package com.educore.ingestion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

/**
 * Lease fence of chunk writes (AC-17). Inside the chunk transaction, before any row of the chunk is written, the
 * chunk takes the run's fence lock in shared mode and then reads its {@code job_log} row: the run must still be
 * open, owned by this instance and within its lease. Every close of a run takes the same lock exclusively
 * ({@link IngestionLedger#close}, {@link IngestionLedger#failExpired}), so a close waits for the chunks in flight,
 * and every chunk that starts after the close fails with {@link LeaseLostException}: its transaction rolls back and
 * nothing of it is written.
 * <p>
 * The fence lock is a transaction-scoped PostgreSQL advisory lock ({@value #LOCK_CLASS}, run id). Chunks of one run
 * share it, so the step's threads are not serialised; unlike a row lock, the lock manager queues a new shared
 * request behind a waiting exclusive one, so a busy run cannot starve the close.
 */
@Component
public class IngestionFence {

    /** First key of the fence lock (the second is the run id); no other advisory lock uses it. */
    static final int LOCK_CLASS = 0x45444331;

    private static final String LOCK_SHARED = "SELECT pg_advisory_xact_lock_shared(?, ?)";
    private static final String LOCK_EXCLUSIVE = "SELECT pg_advisory_xact_lock(?, ?)";
    private static final String READ_RUN = "SELECT owner, status, lease_until FROM job_log WHERE id = ?";

    private record Run(String owner, String status, Timestamp leaseUntil) {
    }

    private final JdbcTemplate jdbc;
    private final IngestionInstance instance;
    private final Clock clock;

    public IngestionFence(JdbcTemplate jdbc, IngestionInstance instance, Clock clock) {
        this.jdbc = jdbc;
        this.instance = instance;
        this.clock = clock;
    }

    /**
     * Verifies, and locks for the rest of the caller's transaction, that run {@code jobLogId} may still be
     * written by this instance.
     *
     * @throws LeaseLostException when the run is closed, owned by another instance or its lease expired
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void verify(long jobLogId) {
        jdbc.query(LOCK_SHARED, rs -> { }, LOCK_CLASS, lockKey(jobLogId));
        // Read after the lock: a close that committed meanwhile is visible (read committed, new statement).
        List<Run> runs = jdbc.query(READ_RUN, (rs, row) -> new Run(rs.getString("owner"), rs.getString("status"),
                rs.getTimestamp("lease_until")), jobLogId);
        if (runs.isEmpty()) {
            throw new LeaseLostException(jobLogId);
        }
        Run run = runs.get(0);
        boolean leaseValid = run.leaseUntil() != null && !run.leaseUntil().toInstant().isBefore(clock.instant());
        if (run.status() != null || !instance.id().equals(run.owner()) || !leaseValid) {
            throw new LeaseLostException(jobLogId);
        }
    }

    /**
     * Takes the run's fence lock exclusively for the caller's transaction: waits for the chunks in flight and holds
     * back new ones until the transaction ends. Called by {@link IngestionLedger} inside the closing transaction.
     */
    static void lockForClose(JdbcTemplate jdbc, long jobLogId) {
        jdbc.query(LOCK_EXCLUSIVE, rs -> { }, LOCK_CLASS, lockKey(jobLogId));
    }

    private static int lockKey(long jobLogId) {
        return (int) (jobLogId & Integer.MAX_VALUE);
    }
}
