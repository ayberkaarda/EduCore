package com.educore.common.web;

import com.educore.config.EduCoreProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;

/**
 * Problem Details for the feature controllers (account, course, enrollment, ipaccess, ingestion, audit).
 * Handles {@link ApiProblemException}, optimistic-lock conflicts (409 {@code request/concurrent-modification})
 * and malformed requests (400 {@code request/invalid}); every other
 * exception falls through to the remaining handlers (access denied stays a 403 from Spring Security). The
 * application-wide problem handler with field-level validation errors arrives with P4.
 */
@RestControllerAdvice(basePackages = {"com.educore.account", "com.educore.course", "com.educore.enrollment",
        "com.educore.ipaccess", "com.educore.ingestion", "com.educore.security.audit"})
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiExceptionHandler {

    static final String INVALID_REQUEST = "request/invalid";
    static final String CONCURRENT_MODIFICATION = "request/concurrent-modification";

    private final String problemBase;

    public ApiExceptionHandler(EduCoreProperties properties) {
        String base = properties.problems().baseUrl().toString();
        this.problemBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> handle(ApiProblemException e) {
        return problem(e.status(), e.code(), e.title());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HandlerMethodValidationException.class})
    ResponseEntity<ProblemDetail> invalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "The request is invalid.");
    }

    /** A write based on a stale read lost against a concurrent change ({@code @Version} conflict). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> concurrentModification() {
        return problem(HttpStatus.CONFLICT, CONCURRENT_MODIFICATION,
                "The record was changed by another request. Reload it and try again.");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(problemBase + "/" + code));
        problem.setTitle(title);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
