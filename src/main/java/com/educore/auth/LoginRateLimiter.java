package com.educore.auth;

import java.time.Duration;

/**
 * Per-client-IP limit on login attempts. The in-process implementation is
 * {@link CaffeineBucketLoginRateLimiter}; a shared store (e.g. Redis) can replace it behind this interface.
 */
public interface LoginRateLimiter {

    /** Consumes one attempt for {@code clientIp}. */
    Decision tryAcquire(String clientIp);

    /** Outcome of one attempt; {@code retryAfter} is zero when allowed. */
    record Decision(boolean allowed, Duration retryAfter) {

        static Decision allow() {
            return new Decision(true, Duration.ZERO);
        }

        static Decision reject(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }
    }
}
