package com.educore.ratelimit;

import java.time.Duration;

/**
 * Token buckets keyed by caller. The in-process implementation is {@link CaffeineRateLimitStore}; a shared
 * store (Redis, BACKLOG) can replace it behind this interface when the API runs on several instances.
 */
public interface RateLimitStore {

    /** Takes one token from the bucket of {@code key}, created with {@code perMinute} tokens on first use. */
    Decision tryConsume(String key, int perMinute);

    /** Outcome of one request; {@code retryAfter} is zero when allowed. */
    record Decision(boolean allowed, Duration retryAfter) {

        public static Decision allow() {
            return new Decision(true, Duration.ZERO);
        }

        public static Decision reject(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }
    }
}
