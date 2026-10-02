package com.educore.ratelimit;

import com.educore.config.EduCoreProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bucket4j token buckets in a bounded Caffeine cache, each refilled in full every minute and dropped after two
 * idle minutes (by then it would be full again, so dropping it loses nothing).
 * <p>
 * Admission instead of eviction: at most {@code educore.ratelimit.max-tracked-keys} callers get a bucket of
 * their own. When the store is full, a caller without a bucket is not given one (an existing bucket is never
 * evicted to make room, so flooding the store with new addresses cannot reset an exhausted bucket); such
 * callers share one overflow bucket of {@code educore.ratelimit.overflow-per-minute} requests until idle
 * buckets expire. The Caffeine size bound sits above the admission limit as a hard memory cap only.
 */
@Component
public class CaffeineRateLimitStore implements RateLimitStore {

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final long maxTrackedKeys;
    private final Cache<String, Bucket> buckets;
    private final Bucket overflow;

    @Autowired
    public CaffeineRateLimitStore(EduCoreProperties properties) {
        this(properties.ratelimit().maxTrackedKeys(), properties.ratelimit().overflowPerMinute());
    }

    /** A store of its own (not the shared bean), e.g. for the login limiter; same admission and overflow policy. */
    public CaffeineRateLimitStore(long maxTrackedKeys, int overflowPerMinute) {
        this.maxTrackedKeys = maxTrackedKeys;
        this.buckets = Caffeine.newBuilder()
                // Concurrent admissions can overshoot the admission check slightly; the hard cap leaves room.
                .maximumSize(maxTrackedKeys + Math.max(64, maxTrackedKeys / 10))
                .expireAfterAccess(WINDOW.multipliedBy(2))
                .build();
        this.overflow = newBucket(overflowPerMinute);
    }

    @Override
    public Decision tryConsume(String key, int perMinute) {
        Bucket bucket = buckets.getIfPresent(key);
        if (bucket == null) {
            bucket = buckets.estimatedSize() < maxTrackedKeys
                    ? buckets.get(key, ignored -> newBucket(perMinute))
                    : overflow;
        }
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? Decision.allow() : Decision.reject(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private static Bucket newBucket(int perMinute) {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(perMinute).refillIntervally(perMinute, WINDOW))
                .build();
    }

    /** Number of buckets currently held (after pending maintenance ran). */
    long size() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }
}
