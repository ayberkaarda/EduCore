package com.educore.auth;

import com.educore.common.web.ApiProblemException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * An authentication failure answered as an RFC 9457 problem whose {@code type} ends in {@code code}.
 * {@link com.educore.common.web.ProblemDetailsAdvice} renders it like every other {@link ApiProblemException};
 * {@link RefreshCookieProblemHeaders} clears the refresh cookie when {@link #clearRefreshCookie()} is set.
 * Texts are fixed per code and never contain request data.
 */
public class AuthProblemException extends ApiProblemException {

    private final boolean clearRefreshCookie;

    private AuthProblemException(HttpStatus status, String code, String title, Duration retryAfter,
                                 boolean clearRefreshCookie, Map<String, Object> properties) {
        super(status, code, title, retryAfter, properties);
        this.clearRefreshCookie = clearRefreshCookie;
    }

    private static AuthProblemException of(HttpStatus status, String code, String title) {
        return new AuthProblemException(status, code, title, null, false, Map.of());
    }

    /** Unknown username and wrong password produce exactly this response. */
    static AuthProblemException invalidCredentials() {
        return of(HttpStatus.UNAUTHORIZED, "auth/invalid-credentials", "Invalid username or password");
    }

    static AuthProblemException locked(Duration retryAfter) {
        return new AuthProblemException(HttpStatus.LOCKED, "auth/account-locked",
                "Too many failed attempts; try again later", retryAfter, false, Map.of());
    }

    static AuthProblemException tooManyAttempts(Duration retryAfter) {
        return new AuthProblemException(HttpStatus.TOO_MANY_REQUESTS, "auth/too-many-attempts",
                "Too many login attempts; try again later", retryAfter, false, Map.of());
    }

    static AuthProblemException invalidRefreshToken() {
        return new AuthProblemException(HttpStatus.UNAUTHORIZED, "auth/invalid-refresh-token",
                "Session expired; sign in again", null, true, Map.of());
    }

    static AuthProblemException originRejected() {
        return of(HttpStatus.FORBIDDEN, "auth/origin-rejected", "Request origin is not allowed");
    }

    static AuthProblemException invalidCurrentPassword() {
        return of(HttpStatus.BAD_REQUEST, "auth/invalid-current-password", "Current password is incorrect");
    }

    /** {@code violations} are the stable rule names of {@link PasswordPolicy}, never the password itself. */
    static AuthProblemException passwordPolicy(List<String> violations) {
        return new AuthProblemException(HttpStatus.BAD_REQUEST, "auth/password-policy",
                "New password does not meet the password policy", null, false,
                Map.of("violations", List.copyOf(violations)));
    }

    boolean clearRefreshCookie() {
        return clearRefreshCookie;
    }
}
