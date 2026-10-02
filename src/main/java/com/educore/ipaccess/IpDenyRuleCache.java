package com.educore.ipaccess;

import com.educore.config.EduCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The deny rules in force, held in memory so {@link IpAccessControlFilter} never queries the database per
 * request. The snapshot is reloaded at most every {@code educore.ipaccess.deny-cache-ttl} (default 60 s) and
 * dropped at once on every change made through this instance ({@link #invalidateAfterCommit()}); another
 * instance sees the change after at most one TTL. A rule whose {@code expiresAt} passes while the snapshot is
 * cached stops matching at that instant.
 * <p>
 * Load failures (database unavailable): the last loaded snapshot stays in use (stale rules are safer than no
 * rules) and a reload is retried after {@link #RETRY_AFTER_FAILURE}. When no snapshot was ever loaded the
 * failure is thrown ({@link UnavailableException}) and the filter answers 503 {@code ipaccess/unavailable}:
 * the deny list fails closed rather than silently admitting denied clients.
 */
@Component
public class IpDenyRuleCache {

    static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds(5);
    private static final Logger log = LoggerFactory.getLogger(IpDenyRuleCache.class);

    /** What an address may do. */
    public enum Denial {
        /** No active rule covers the address. */
        NONE,
        /** Only AUTO rules cover it: the login endpoint is refused, everything else is served. */
        LOGIN,
        /** A MANUAL rule covers it: every request is refused. */
        ALL
    }

    private final IpDenyRuleRepository repository;
    private final Clock clock;
    private final Duration ttl;
    private volatile Snapshot snapshot;
    private volatile Instant nextLoad = Instant.MIN;
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

    @Autowired
    public IpDenyRuleCache(IpDenyRuleRepository repository, Clock clock, EduCoreProperties properties) {
        this(repository, clock, properties.ipaccess().denyCacheTtl());
    }

    IpDenyRuleCache(IpDenyRuleRepository repository, Clock clock, Duration ttl) {
        this.repository = repository;
        this.clock = clock;
        this.ttl = ttl;
    }

    /** True when any active rule (MANUAL or AUTO) covers {@code address}. */
    public boolean isDenied(Ipv4 address) {
        return denial(address) != Denial.NONE;
    }

    /** The strongest active rule covering {@code address}. */
    public Denial denial(Ipv4 address) {
        Instant now = clock.instant();
        return current(now).denial(address.toLong(), now.toEpochMilli());
    }

    /** Drops the snapshot now and again after the current transaction commits (if one is active). */
    public void invalidateAfterCommit() {
        generation.incrementAndGet();
        nextLoad = Instant.MIN;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    generation.incrementAndGet();
                    nextLoad = Instant.MIN;
                }
            });
        }
    }

    private Snapshot current(Instant now) {
        Snapshot loaded = snapshot;
        if (loaded != null && now.isBefore(nextLoad)) {
            return loaded;
        }
        synchronized (this) {
            if (snapshot != null && now.isBefore(nextLoad)) {
                return snapshot;
            }
            long requested = generation.get();
            try {
                Snapshot fresh = load(now);
                snapshot = fresh;
                // An invalidation during the load forces another load on the next request.
                nextLoad = generation.get() == requested ? now.plus(ttl) : Instant.MIN;
                return fresh;
            } catch (RuntimeException e) {
                if (snapshot == null) {
                    throw new UnavailableException(e);
                }
                log.warn("Reloading IP deny rules failed ({}); the previous rules stay in force",
                        e.getClass().getSimpleName());
                nextLoad = now.plus(RETRY_AFTER_FAILURE);
                return snapshot;
            }
        }
    }

    private Snapshot load(Instant now) {
        List<IpDenyRule> rules = repository.findActive(now);
        long[] starts = new long[rules.size()];
        long[] ends = new long[rules.size()];
        long[] expires = new long[rules.size()];
        boolean[] manual = new boolean[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            IpDenyRule rule = rules.get(i);
            starts[i] = rule.getStartIp();
            ends[i] = rule.getEndIp();
            expires[i] = rule.getExpiresAt() == null ? Long.MAX_VALUE : rule.getExpiresAt().toEpochMilli();
            manual[i] = rule.getSource() != IpDenyRuleSource.AUTO;
        }
        return new Snapshot(starts, ends, expires, manual);
    }

    /** The deny rules could not be loaded and no earlier snapshot exists. */
    public static final class UnavailableException extends RuntimeException {
        UnavailableException(Throwable cause) {
            super("IP deny rules unavailable", cause);
        }
    }

    private record Snapshot(long[] starts, long[] ends, long[] expiresAtMillis, boolean[] manual) {

        Denial denial(long address, long nowMillis) {
            Denial result = Denial.NONE;
            for (int i = 0; i < starts.length; i++) {
                if (starts[i] <= address && address <= ends[i] && expiresAtMillis[i] > nowMillis) {
                    if (manual[i]) {
                        return Denial.ALL;
                    }
                    result = Denial.LOGIN;
                }
            }
            return result;
        }
    }
}
