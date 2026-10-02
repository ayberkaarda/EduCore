package com.educore.weather;

import com.educore.common.web.ApiProblemException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Map;

/** 429 problem with a {@code Retry-After} header (whole seconds, at least 1). */
public class RateLimitedException extends ApiProblemException {

    public RateLimitedException(String code, String title, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, title,
                Duration.ofSeconds(Math.max(1, (retryAfter.toMillis() + 999) / 1000)), Map.of());
    }
}
