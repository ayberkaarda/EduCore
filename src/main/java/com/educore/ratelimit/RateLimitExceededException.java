package com.educore.ratelimit;

import com.educore.common.web.ApiProblemException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Map;

/**
 * 429 {@code rate-limit/exceeded} with {@code Retry-After} (whole seconds, at least 1) for a named bucket
 * ({@link NamedRateLimits}); the same code and title as {@link RateLimitFilter}.
 */
public class RateLimitExceededException extends ApiProblemException {

    RateLimitExceededException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, RateLimitFilter.CODE, RateLimitFilter.TITLE, retryAfter, Map.of());
    }
}
