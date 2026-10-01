package com.educore.auth;

import com.educore.config.EduCoreProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bucket4j token bucket per client IP: {@code educore.security.login.ip-attempts-per-minute} attempts
 * (default 10), refilled in full every minute. Buckets live in a bounded Caffeine cache and are dropped
 * after two idle minutes, when they would be full again anyway.
 */
@Component
public class CaffeineBucketLoginRateLimiter implements LoginRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final long MAX_TRACKED_IPS = 100_000;

    private final int attemptsPerMinute;
    private final Cache<String, Bucket> buckets;

    public CaffeineBucketLoginRateLimiter(EduCoreProperties properties) {
        this.attemptsPerMinute = properties.security().login().ipAttemptsPerMinute();
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_IPS)
                .expireAfterAccess(WINDOW.multipliedBy(2))
                .build();
    }

    @Override
    public Decision tryAcquire(String clientIp) {
        Bucket bucket = buckets.get(clientIp, ip -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Decision.allow();
        }
        return Decision.reject(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(attemptsPerMinute).refillIntervally(attemptsPerMinute, WINDOW))
                .build();
    }
}
