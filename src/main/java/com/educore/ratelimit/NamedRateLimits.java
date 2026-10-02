package com.educore.ratelimit;

import org.springframework.stereotype.Component;

/**
 * Per-feature token buckets on top of the request-level limits of {@link RateLimitFilter}, kept in the same
 * {@link RateLimitStore}. A bucket is named here and keyed by its subject (for example an account id); its key
 * space ({@code <name>:<subject>}) never meets the filter's {@code public:}, {@code account:} and
 * {@code anonymous:} keys.
 */
@Component
public class NamedRateLimits {

    /** {@code GET /api/v1/me/export}: per account ({@code educore.lifecycle.export-per-minute}). */
    public static final String DATA_EXPORT = "data-export";

    private final RateLimitStore store;

    public NamedRateLimits(RateLimitStore store) {
        this.store = store;
    }

    /**
     * Takes one token from bucket {@code name} of {@code subject} ({@code perMinute} tokens, refilled every
     * minute).
     *
     * @throws RateLimitExceededException when the bucket is empty (429 {@code rate-limit/exceeded} with
     *                                    {@code Retry-After})
     */
    public void consume(String name, Object subject, int perMinute) {
        RateLimitStore.Decision decision = store.tryConsume(name + ":" + subject, perMinute);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfter());
        }
    }
}
