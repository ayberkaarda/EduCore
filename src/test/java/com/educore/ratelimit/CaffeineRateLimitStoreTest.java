package com.educore.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineRateLimitStoreTest {

    @Test
    void bucketAllowsItsCapacityThenRejectsWithAWait() {
        CaffeineRateLimitStore store = new CaffeineRateLimitStore(100, 1000);

        for (int i = 0; i < 3; i++) {
            assertThat(store.tryConsume("anonymous:203.0.113.1", 3).allowed()).isTrue();
        }
        RateLimitStore.Decision rejected = store.tryConsume("anonymous:203.0.113.1", 3);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isPositive().isLessThanOrEqualTo(CaffeineRateLimitStore.WINDOW);
        assertThat(store.tryConsume("anonymous:203.0.113.2", 3).allowed()).isTrue();
    }

    @Test
    void theNumberOfBucketsStaysBoundedHoweverManyKeysArrive() {
        CaffeineRateLimitStore store = new CaffeineRateLimitStore(50, 1_000_000);

        for (int i = 0; i < 10_000; i++) {
            store.tryConsume("anonymous:key-" + i, 60);
        }

        assertThat(store.size()).isLessThanOrEqualTo(50 + 64);
    }

    /**
     * Flooding the full store with new keys must not evict an exhausted bucket (which would come back full);
     * the newcomers share the overflow bucket instead, which is exhausted in turn.
     */
    @Test
    void exhaustedBucketSurvivesEvictionPressureAndNewcomersShareTheOverflow() {
        CaffeineRateLimitStore store = new CaffeineRateLimitStore(10, 20);
        for (int i = 0; i < 3; i++) {
            store.tryConsume("anonymous:attacker", 3);
        }
        assertThat(store.tryConsume("anonymous:attacker", 3).allowed()).isFalse();
        for (int i = 0; i < 9; i++) {
            assertThat(store.tryConsume("anonymous:filler-" + i, 3).allowed()).isTrue();
        }

        int overflowAllowed = 0;
        for (int i = 0; i < 5_000; i++) {
            if (store.tryConsume("anonymous:newcomer-" + i, 3).allowed()) {
                overflowAllowed++;
            }
        }

        assertThat(overflowAllowed).isEqualTo(20);
        assertThat(store.tryConsume("anonymous:attacker", 3).allowed()).as("still exhausted").isFalse();
        assertThat(store.tryConsume("anonymous:filler-0", 3).allowed()).as("own bucket kept").isTrue();
        assertThat(store.size()).isLessThanOrEqualTo(10 + 64);
    }
}
