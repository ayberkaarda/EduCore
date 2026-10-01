package com.educore.auth;

import com.educore.config.EduCoreProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Duration;

/**
 * Renders auth failures as {@code application/problem+json}. {@code type} is
 * {@code <educore.problems.base-url>/<code>}; {@code Retry-After} (seconds) accompanies 423 and 429.
 * Scoped to the auth controller; the application-wide problem handler arrives with P4.
 */
@RestControllerAdvice(assignableTypes = AuthController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    private final String problemBase;
    private final RefreshCookies refreshCookies;

    public AuthExceptionHandler(EduCoreProperties properties, RefreshCookies refreshCookies) {
        String base = properties.problems().baseUrl().toString();
        this.problemBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.refreshCookies = refreshCookies;
    }

    @ExceptionHandler(AuthProblemException.class)
    ResponseEntity<ProblemDetail> handle(AuthProblemException e) {
        ProblemDetail problem = ProblemDetail.forStatus(e.status());
        problem.setType(URI.create(problemBase + "/" + e.code()));
        problem.setTitle(e.title());
        if (!e.violations().isEmpty()) {
            problem.setProperty("violations", e.violations());
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (e.retryAfter() != null) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(e.retryAfter())));
        }
        if (e.clearRefreshCookie()) {
            response.header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString());
        }
        return response.body(problem);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalidRequest() {
        return handle(AuthProblemException.invalidRequest());
    }

    static long retryAfterSeconds(Duration duration) {
        long seconds = duration.toSeconds() + (duration.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
