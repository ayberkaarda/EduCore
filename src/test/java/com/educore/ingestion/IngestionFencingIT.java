package com.educore.ingestion;

import com.educore.entity.JobLog;
import com.educore.entity.JobLogStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-17: lease expiry fences chunk writes. Every chunk verifies and share-locks its run before writing
 * ({@link IngestionFence}); recovery and the owner close a run only through conditional updates
 * ({@link IngestionLedger}). Once recovery closed a run whose owner stalled, the owner's next chunk is rejected and
 * writes nothing, and the owner cannot overwrite the recovery's outcome.
 */
class IngestionFencingIT extends IngestionIntegrationSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Autowired
    private IngestionLedger ledger;

    @Autowired
    private IngestionFence fence;

    @Autowired
    private IngestionInstance instance;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void afterRecoveryClosedAStalledRunItsNextChunksAreRejectedAndWriteNothing() throws Exception {
        String token = token();
        List<String> numbers = new ArrayList<>();
        StringBuilder csv = new StringBuilder(STUDENT_HEADER);
        for (int i = 0; i < 160; i++) {
            String number = number();
            numbers.add(number);
            csv.append("Fence,Row").append(',').append(number).append('\n');
        }
        Path file = staged("students-fence-" + token + ".csv", csv.toString());
        ExecutorService owner = Executors.newSingleThreadExecutor();
        ExecutorService others = Executors.newFixedThreadPool(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Optional<Long>> run = owner.submit(() -> ingestionService.ingest(file));
            long jobLogId = await(() -> jdbc.queryForList("SELECT id FROM job_log WHERE file_name LIKE ? "
                    + "AND status IS NULL", Long.class, "%" + token + "%").stream().findFirst(), TIMEOUT, "open run");
            await(() -> written(numbers) >= 4 ? Optional.of(true) : Optional.<Boolean>empty(), TIMEOUT,
                    "first chunks written");

            // The owner stalls in the middle of its run: inserts into account wait for this lock (reads do not).
            CountDownLatch locked = new CountDownLatch(1);
            Future<?> stall = others.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbc.execute("LOCK TABLE account IN SHARE MODE");
                        locked.countDown();
                        awaitLatch(release);
                    }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            // Meanwhile its lease runs out and recovery closes the run (recovery "from the future": the lease is
            // long over by then). The close waits for the chunks in flight; chunks after them queue behind it.
            Future<List<String>> close = others.submit(() -> ledger.failExpired(Instant.now().plus(Duration.ofHours(1))));
            await(() -> close.isDone() || waitingFenceLocks() > 0 ? Optional.of(true) : Optional.<Boolean>empty(),
                    TIMEOUT, "recovery reached the fence");
            release.countDown();
            stall.get(30, TimeUnit.SECONDS);
            close.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            int atClose = written(numbers);
            JobLog closed = jobLogRepository.findById(jobLogId).orElseThrow();
            assertThat(closed.getStatus()).isEqualTo(JobLogStatus.FAILED);
            assertThat(closed.getReason()).isEqualTo("INTERRUPTED");

            assertThat(run.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).contains(jobLogId);

            assertThat(written(numbers)).as("rows written after the run was closed").isEqualTo(atClose);
            assertThat(atClose).isLessThan(numbers.size());
            JobLog afterOwner = jobLogRepository.findById(jobLogId).orElseThrow();
            assertThat(afterOwner.getReason()).as("the owner must not overwrite the recovery's outcome")
                    .isEqualTo("INTERRUPTED");
            assertThat(afterOwner.getFinishedAt()).isEqualTo(closed.getFinishedAt());
        } finally {
            release.countDown();
            owner.shutdownNow();
            others.shutdownNow();
        }
    }

    @Test
    void theFenceAdmitsOnlyAnOpenUnexpiredRunOfThisInstance() {
        String token = token();
        Instant now = Instant.now();
        long mine = ledger.open("fence-mine-" + token + ".csv", now, instance.id(), now.plusSeconds(600), null);
        long foreign = ledger.open("fence-foreign-" + token + ".csv", now, "instance-other", now.plusSeconds(600), null);
        long expired = ledger.open("fence-expired-" + token + ".csv", now, instance.id(), now.minusSeconds(1), null);
        long closed = ledger.open("fence-closed-" + token + ".csv", now, instance.id(), now.plusSeconds(600), null);
        assertThat(ledger.close(closed, ImportOutcome.rejected(IngestionReason.INTERRUPTED, null), now, instance.id()))
                .isTrue();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> fence.verify(mine));
        for (long rejected : new long[]{foreign, expired, closed}) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> fence.verify(rejected)))
                    .isInstanceOf(LeaseLostException.class);
        }
        // Another owner can neither close nor renew this instance's run; an expired lease is not revived.
        assertThat(ledger.close(mine, ImportOutcome.rejected(IngestionReason.INTERRUPTED, null), now, "instance-other"))
                .isFalse();
        ledger.renewLeases(instance.id(), now, now.plusSeconds(900));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> fence.verify(expired)))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    void recoveryWaitsForAChunkInFlightAndTheNextChunkIsRejected() throws Exception {
        String token = token();
        Instant now = Instant.now();
        long run = ledger.open("fence-flight-" + token + ".csv", now, instance.id(), now.plusSeconds(600), null);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch verified = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> chunk = threads.submit(() -> transaction.executeWithoutResult(status -> {
                fence.verify(run);
                verified.countDown();
                awaitLatch(release);
            }));
            assertThat(verified.await(30, TimeUnit.SECONDS)).isTrue();
            // Recovery from the future (the run's lease is over by then): the close must wait for the chunk.
            Future<List<String>> close = threads.submit(() -> ledger.failExpired(now.plusSeconds(3600)));
            pause(Duration.ofMillis(500));
            assertThat(close.isDone()).as("close must wait for the chunk holding the run").isFalse();

            release.countDown();
            chunk.get(30, TimeUnit.SECONDS);
            close.get(30, TimeUnit.SECONDS);
            assertThat(jobLogRepository.findById(run).orElseThrow().getReason()).isEqualTo("INTERRUPTED");
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> fence.verify(run)))
                    .isInstanceOf(LeaseLostException.class);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    private int waitingFenceLocks() {
        Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' "
                + "AND classid::bigint = ? AND NOT granted", Integer.class, (long) IngestionFence.LOCK_CLASS);
        return waiting == null ? 0 : waiting;
    }

    private int written(List<String> numbers) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM account WHERE student_number = ANY (?)",
                Integer.class, (Object) numbers.toArray(String[]::new));
        return count == null ? 0 : count;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
