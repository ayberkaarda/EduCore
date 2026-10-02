package com.educore.lifecycle;

import com.educore.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Purges accounts whose deletion grace period ended ({@code status = PENDING_DELETION} and
 * {@code delete_after <= now}), one account per transaction, at most {@code educore.lifecycle.purge-batch-size}
 * per run, on the schedule {@code educore.lifecycle.purge-cron} (UTC; default nightly at 03:30).
 * <p>
 * Idempotent and safe on several instances or threads at once: each transaction claims the next due row with
 * {@code FOR UPDATE SKIP LOCKED} ({@link AccountRepository#lockNextDueForPurge}), so two runs never purge the
 * same account, a row held by a login or restore in progress is skipped, and a purged row is gone for every
 * later run. A failing account stops the run (logged); the next run retries it.
 */
@Component
public class AccountPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeJob.class);

    private final AccountRepository accounts;
    private final AccountPurger purger;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final int batchSize;

    public AccountPurgeJob(AccountRepository accounts, AccountPurger purger, Clock clock,
                           PlatformTransactionManager transactionManager, LifecycleProperties properties) {
        this.accounts = accounts;
        this.purger = purger;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.batchSize = properties.purgeBatchSize();
    }

    @Scheduled(cron = "${educore.lifecycle.purge-cron:0 30 3 * * *}", zone = "UTC")
    public void scheduledRun() {
        int purged = purgeDue();
        if (purged > 0) {
            log.info("Account purge finished purged={}", purged);
        }
    }

    /** Purges every account that is due now (up to the batch size); returns how many this call purged. */
    public int purgeDue() {
        Instant now = clock.instant();
        int purged = 0;
        while (purged < batchSize) {
            Boolean done;
            try {
                done = transaction.execute(status -> purgeNext(now));
            } catch (RuntimeException e) {
                log.error("Account purge stopped after purged={}", purged, e);
                break;
            }
            if (!Boolean.TRUE.equals(done)) {
                break;
            }
            purged++;
        }
        return purged;
    }

    /** Claims and purges the next due account; false when none is left (or all are held by other runs). */
    private boolean purgeNext(Instant now) {
        List<Long> next = accounts.lockNextDueForPurge(now);
        if (next.isEmpty()) {
            return false;
        }
        return purger.purge(next.get(0), AccountPurger.Trigger.GRACE_EXPIRED) != null;
    }
}
