package com.educore.weather;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

import java.time.Duration;
import java.util.Optional;

/**
 * A Bucket4j token bucket per key (an account id), the same pattern as the login limiter: {@code capacity}
 * requests per {@code window}, refilled in full each window; buckets live in a bounded Caffeine cache and
 * expire after two idle windows, when they would be full again anyway.
 */
public final class PerUserRateLimiter {

    private static final long MAX_TRACKED_KEYS = 100_000;

    private final int capacity;
    private final Duration window;
    private final Cache<Object, Bucket> buckets;

    public PerUserRateLimiter(int capacity, Duration window) {
        this.capacity = capacity;
        this.window = window;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_KEYS)
                .expireAfterAccess(window.multipliedBy(2))
                .build();
    }

    /** Consumes one token; returns the time to wait when none is left. */
    public Optional<Duration> tryAcquire(Object key) {
        ConsumptionProbe probe = buckets.get(key, k -> newBucket()).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillIntervally(capacity, window))
                .build();
    }
}
