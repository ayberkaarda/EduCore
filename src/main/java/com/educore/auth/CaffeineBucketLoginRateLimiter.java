package com.educore.auth;

import com.educore.config.EduCoreProperties;
import com.educore.ipaccess.ClientAddress;
import com.educore.ratelimit.CaffeineRateLimitStore;
import com.educore.ratelimit.RateLimitStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bucket4j token bucket per client: {@code educore.security.login.ip-attempts-per-minute} attempts (default 10),
 * refilled in full every minute.
 * <p>
 * The bucket key is the shared canonical client key {@link ClientAddress#clientKey(String)}:
 * an IPv4 address (IPv4-mapped IPv6 and {@code ip:port} forms normalised to it) or, for native IPv6, the /64
 * network. One subscriber usually holds a whole /64, so keying IPv6 by the full address would give an attacker
 * 2^64 fresh buckets (R-04). The auto-deny failure counter and the login lockout pairs use the same key.
 * <p>
 * Buckets live in a store of their own with the admission policy of the general limiter
 * ({@link CaffeineRateLimitStore}): at most {@value #MAX_TRACKED_CLIENTS} clients get a bucket, an existing bucket
 * is never evicted to make room (flooding with new addresses cannot reset an exhausted one), and newcomers to a
 * full store share one overflow bucket of {@code educore.ratelimit.overflow-per-minute} attempts.
 */
@Component
public class CaffeineBucketLoginRateLimiter implements LoginRateLimiter {

    static final long MAX_TRACKED_CLIENTS = 100_000;
    private static final String PREFIX = "login:";

    private final int attemptsPerMinute;
    private final RateLimitStore store;

    @Autowired
    public CaffeineBucketLoginRateLimiter(EduCoreProperties properties) {
        this(properties.security().login().ipAttemptsPerMinute(),
                new CaffeineRateLimitStore(MAX_TRACKED_CLIENTS, properties.ratelimit().overflowPerMinute()));
    }

    CaffeineBucketLoginRateLimiter(int attemptsPerMinute, RateLimitStore store) {
        this.attemptsPerMinute = attemptsPerMinute;
        this.store = store;
    }

    @Override
    public Decision tryAcquire(String clientIp) {
        RateLimitStore.Decision decision = store.tryConsume(PREFIX + clientKey(clientIp), attemptsPerMinute);
        return decision.allowed() ? Decision.allow() : Decision.reject(decision.retryAfter());
    }

    /** {@link ClientAddress#clientKey(String)}. */
    static String clientKey(String clientIp) {
        return ClientAddress.clientKey(clientIp);
    }
}
