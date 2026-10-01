package com.educore.auth;

import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;

/**
 * An authentication failure answered as an RFC 9457 problem whose {@code type} ends in {@code code}.
 * {@link AuthExceptionHandler} renders it; texts are fixed per code and never contain request data.
 */
public class AuthProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final Duration retryAfter;
    private final boolean clearRefreshCookie;
    private final List<String> violations;

    private AuthProblemException(HttpStatus status, String code, String title, Duration retryAfter,
                                 boolean clearRefreshCookie, List<String> violations) {
        super(code, null, false, false);
        this.status = status;
        this.code = code;
        this.title = title;
        this.retryAfter = retryAfter;
        this.clearRefreshCookie = clearRefreshCookie;
        this.violations = violations;
    }

    /** Unknown username and wrong password produce exactly this response. */
    static AuthProblemException invalidCredentials() {
        return new AuthProblemException(HttpStatus.UNAUTHORIZED, "auth/invalid-credentials",
                "Invalid username or password", null, false, List.of());
    }

    static AuthProblemException locked(Duration retryAfter) {
        return new AuthProblemException(HttpStatus.LOCKED, "auth/account-locked",
                "Too many failed attempts; try again later", retryAfter, false, List.of());
    }

    static AuthProblemException tooManyAttempts(Duration retryAfter) {
        return new AuthProblemException(HttpStatus.TOO_MANY_REQUESTS, "auth/too-many-attempts",
                "Too many login attempts; try again later", retryAfter, false, List.of());
    }

    static AuthProblemException invalidRefreshToken() {
        return new AuthProblemException(HttpStatus.UNAUTHORIZED, "auth/invalid-refresh-token",
                "Session expired; sign in again", null, true, List.of());
    }

    static AuthProblemException originRejected() {
        return new AuthProblemException(HttpStatus.FORBIDDEN, "auth/origin-rejected",
                "Request origin is not allowed", null, false, List.of());
    }

    static AuthProblemException invalidCurrentPassword() {
        return new AuthProblemException(HttpStatus.BAD_REQUEST, "auth/invalid-current-password",
                "Current password is incorrect", null, false, List.of());
    }

    static AuthProblemException passwordPolicy(List<String> violations) {
        return new AuthProblemException(HttpStatus.BAD_REQUEST, "auth/password-policy",
                "New password does not meet the password policy", null, false, List.copyOf(violations));
    }

    static AuthProblemException invalidRequest() {
        return new AuthProblemException(HttpStatus.BAD_REQUEST, "auth/invalid-request",
                "Request body is missing or invalid", null, false, List.of());
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    String title() {
        return title;
    }

    Duration retryAfter() {
        return retryAfter;
    }

    boolean clearRefreshCookie() {
        return clearRefreshCookie;
    }

    List<String> violations() {
        return violations;
    }
}
