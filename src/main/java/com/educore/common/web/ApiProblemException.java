package com.educore.common.web;

import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A client-facing failure, rendered by {@link ProblemDetailsAdvice} as {@code application/problem+json} with
 * {@code type = <educore.problems.base-url>/<code>}, a fixed {@code title} and the stable {@code code}.
 * Titles are constant texts: they never echo request input or exception messages. {@code retryAfter}
 * becomes a {@code Retry-After} header (seconds); {@code properties} are extra problem members that subclasses
 * fill with machine-readable values only (for example password policy rule names).
 * <p>
 * No stack trace is captured: this is an expected outcome, not a defect.
 */
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final Duration retryAfter;
    private final Map<String, Object> properties;

    public ApiProblemException(HttpStatus status, String code, String title) {
        this(status, code, title, null, Map.of());
    }

    protected ApiProblemException(HttpStatus status, String code, String title, Duration retryAfter,
                                  Map<String, Object> properties) {
        super(code, null, false, false);
        this.status = status;
        this.code = code;
        this.title = title;
        this.retryAfter = retryAfter;
        this.properties = new LinkedHashMap<>(properties);
    }

    public static ApiProblemException notFound(String code, String title) {
        return new ApiProblemException(HttpStatus.NOT_FOUND, code, title);
    }

    public static ApiProblemException conflict(String code, String title) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, title);
    }

    public static ApiProblemException badRequest(String code, String title) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, code, title);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    /** How long the client should wait before retrying, or {@code null}. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** Extra problem members (read-only view). */
    public Map<String, Object> properties() {
        return Map.copyOf(properties);
    }
}
