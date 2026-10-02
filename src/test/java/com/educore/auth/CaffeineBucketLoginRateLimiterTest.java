package com.educore.auth;

import com.educore.ratelimit.CaffeineRateLimitStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The login limiter keys its buckets by the shared canonical client key (R-04): rotating IPv6 addresses inside one
 * /64, or spelling one IPv4 address in its mapped forms, never yields a fresh bucket; a full store admits no new
 * bucket and never evicts an exhausted one.
 */
class CaffeineBucketLoginRateLimiterTest {

    private static CaffeineBucketLoginRateLimiter limiter(int perMinute) {
        return new CaffeineBucketLoginRateLimiter(perMinute, new CaffeineRateLimitStore(1_000, 1_000));
    }

    @Test
    void everyAddressOfOneIpv6Slash64SharesOneBucket() {
        CaffeineBucketLoginRateLimiter limiter = limiter(3);

        assertThat(limiter.tryAcquire("2001:db8:aa:bb::1").allowed()).isTrue();
        assertThat(limiter.tryAcquire("2001:db8:aa:bb:ffff:ffff:ffff:fffe").allowed()).isTrue();
        assertThat(limiter.tryAcquire("[2001:db8:aa:bb::REMOVED-DB-PASSWORD]:5555").allowed()).isTrue();
        LoginRateLimiter.Decision fourth = limiter.tryAcquire("2001:db8:aa:bb:1:2:3:4");

        assertThat(fourth.allowed()).as("a fourth address of the same /64").isFalse();
        assertThat(fourth.retryAfter()).isPositive();
        assertThat(limiter.tryAcquire("2001:db8:aa:bc::1").allowed()).as("the neighbouring /64").isTrue();
    }

    @Test
    void ipv4MappedFormsShareTheBucketOfTheIpv4Address() {
        CaffeineBucketLoginRateLimiter limiter = limiter(4);

        assertThat(limiter.tryAcquire("198.18.7.7").allowed()).isTrue();
        assertThat(limiter.tryAcquire("::ffff:198.18.7.7").allowed()).isTrue();
        assertThat(limiter.tryAcquire("::ffff:c612:707").allowed()).isTrue();
        assertThat(limiter.tryAcquire("198.18.7.7:40000").allowed()).isTrue();

        assertThat(limiter.tryAcquire("0:0:0:0:0:ffff:c612:707").allowed()).isFalse();
        assertThat(limiter.tryAcquire("198.18.7.8").allowed()).isTrue();
    }

    @Test
    void theKeyFunctionIsTheSharedCanonicalClientKey() {
        assertThat(CaffeineBucketLoginRateLimiter.clientKey("2001:db8:aa:bb::1"))
                .isEqualTo(CaffeineBucketLoginRateLimiter.clientKey("2001:0db8:00aa:00bb:9:8:7:6"))
                .isEqualTo("2001:0db8:00aa:00bb::/64");
        assertThat(CaffeineBucketLoginRateLimiter.clientKey("::ffff:198.18.7.7"))
                .isEqualTo(CaffeineBucketLoginRateLimiter.clientKey("198.18.7.7"))
                .isEqualTo("198.18.7.7");
    }

    /** Same admission policy as the general limiter: a full store never evicts an exhausted bucket. */
    @Test
    void floodingAFullStoreNeitherResetsAnExhaustedBucketNorGrowsIt() {
        CaffeineBucketLoginRateLimiter limiter = new CaffeineBucketLoginRateLimiter(2,
                new CaffeineRateLimitStore(5, 10));
        limiter.tryAcquire("203.0.113.9");
        limiter.tryAcquire("203.0.113.9");
        assertThat(limiter.tryAcquire("203.0.113.9").allowed()).isFalse();

        int overflowAllowed = 0;
        for (int i = 0; i < 2_000; i++) {
            if (limiter.tryAcquire("2001:db8:" + Integer.toHexString(i) + "::1").allowed()) {
                overflowAllowed++;
            }
        }

        assertThat(limiter.tryAcquire("203.0.113.9").allowed()).as("still exhausted").isFalse();
        // 4 newcomers got buckets of their own (2 attempts each) before the store was full; the rest shared 10.
        assertThat(overflowAllowed).isEqualTo(4 * 1 + 10);
    }
}
