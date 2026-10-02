package com.educore.ipaccess;

import com.educore.config.EduCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes expired deny rules every {@code educore.ipaccess.cleanup-interval} (default 10 minutes). Expired
 * rules never match before that (the cache and its loader both compare {@code expiresAt} with the clock); the
 * purge only keeps the table small.
 */
@Component
public class IpDenyRuleCleanup implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(IpDenyRuleCleanup.class);

    private final IpDenyRuleRepository repository;
    private final IpDenyRuleCache cache;
    private final Clock clock;
    private final Duration interval;
    private final TransactionTemplate transaction;

    public IpDenyRuleCleanup(IpDenyRuleRepository repository, IpDenyRuleCache cache, Clock clock,
                             EduCoreProperties properties, PlatformTransactionManager transactionManager) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.repository = repository;
        this.cache = cache;
        this.clock = clock;
        this.interval = properties.ipaccess().cleanupInterval();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::purgeExpiredSafely, interval);
    }

    /** Deletes every rule whose {@code expiresAt} has passed; returns how many were deleted. */
    public int purgeExpired() {
        Integer deleted = transaction.execute(status -> repository.deleteExpired(clock.instant()));
        int count = deleted == null ? 0 : deleted;
        if (count > 0) {
            cache.invalidateAfterCommit();
            log.info("IP_DENY_RULES_PURGED count={}", count);
        }
        return count;
    }

    private void purgeExpiredSafely() {
        try {
            purgeExpired();
        } catch (RuntimeException e) {
            log.warn("Purging expired IP deny rules failed: {}", e.getClass().getSimpleName());
        }
    }
}
