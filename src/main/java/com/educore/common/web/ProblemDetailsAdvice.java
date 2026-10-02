package com.educore.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Duration;
import java.util.List;

/**
 * The single exception-to-problem mapping of the API (RFC 9457, {@code application/problem+json}).
 * <ul>
 *   <li>{@link ApiProblemException} (feature and auth failures): its status, code and title, plus
 *       {@code Retry-After} and the headers of every {@link ProblemHeaderContributor};</li>
 *   <li>request validation ({@code @Valid} bodies, {@code @Validated} parameters, unreadable JSON, unknown
 *       enum values, type mismatches, missing parameters): 400 {@code request/invalid} with
 *       {@code errors[{field, code}]} ({@code auth/invalid-request} under {@code /api/v1/auth/});</li>
 *   <li>Spring MVC infrastructure (inherited from {@link ResponseEntityExceptionHandler}): 404, 405, 406, 413,
 *       415 and the other standard statuses with the generic codes of {@link Problems#forStatus};</li>
 *   <li>conflicts: optimistic lock 409 {@code request/concurrent-modification}, database constraint 409
 *       {@code request/conflict};</li>
 *   <li>a body above the {@link RequestBodyLimitFilter} limit: 413 {@code request/payload-too-large};</li>
 *   <li>anything else: 500 {@code server/internal-error} carrying only the {@code correlationId}
 *       ({@link CorrelationIds}: the request's {@code X-Request-Id}, also on async dispatches); the exception is
 *       logged with that id.</li>
 * </ul>
 * Access-denied and authentication exceptions raised by method security are re-thrown so that Spring
 * Security's entry point and access-denied handler (same problem shape) decide between 401 and 403.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    static final String AUTH_INVALID_REQUEST = "auth/invalid-request";
    private static final String AUTH_PATH = "/api/v1/auth/";
    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    private final Problems problems;
    private final List<ProblemHeaderContributor> headerContributors;

    public ProblemDetailsAdvice(Problems problems, List<ProblemHeaderContributor> headerContributors) {
        this.problems = problems;
        this.headerContributors = List.copyOf(headerContributors);
    }

    @ExceptionHandler(ApiProblemException.class)
    public ResponseEntity<ProblemDetail> handleApiProblem(ApiProblemException e, HttpServletRequest request) {
        ProblemDetail problem = problems.create(e.status(), e.code(), e.title(), request.getRequestURI());
        e.properties().forEach(problem::setProperty);
        HttpHeaders headers = new HttpHeaders();
        if (e.retryAfter() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(e.retryAfter())));
        }
        headerContributors.forEach(contributor -> contributor.contribute(e, headers));
        return respond(problem, headers);
    }

    /** Violations of {@code @Validated} controller parameters (method validation through the AOP proxy). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException e,
                                                                   HttpServletRequest request) {
        return respond(invalid(ValidationErrors.of(e), request.getRequestURI()), new HttpHeaders());
    }

    /** A write based on a stale read lost against a concurrent change ({@code @Version} conflict). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(HttpServletRequest request) {
        return respond(problems.create(HttpStatus.CONFLICT, Problems.CONCURRENT_MODIFICATION,
                "The record was changed by another request. Reload it and try again.", request.getRequestURI()),
                new HttpHeaders());
    }

    /** A unique or foreign key constraint rejected the write (e.g. deleting a course that has enrollments). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(HttpServletRequest request) {
        return respond(problems.forStatus(HttpStatus.CONFLICT, request.getRequestURI()), new HttpHeaders());
    }

    /** Lets Spring Security's ExceptionTranslationFilter answer 401 or 403 (same problem shape). */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void rethrowSecurityException(RuntimeException e) {
        throw e;
    }

    /** The request body exceeded {@link RequestBodyLimitFilter}'s limit while it was being read. */
    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ResponseEntity<ProblemDetail> handleBodyTooLarge(HttpServletRequest request) {
        return respond(problems.forStatus(HttpStatus.PAYLOAD_TOO_LARGE, request.getRequestURI()), new HttpHeaders());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request,
                                                          HttpServletResponse response) {
        if (RequestBodyTooLargeException.isCauseOf(e)) {
            return handleBodyTooLarge(request);
        }
        return respond(serverError(HttpStatus.INTERNAL_SERVER_ERROR, e, request, response), new HttpHeaders());
    }

    /** Every Spring MVC standard exception ends here (see {@link ResponseEntityExceptionHandler}). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpServletRequest servletRequest = null;
        HttpServletResponse servletResponse = null;
        if (request instanceof ServletWebRequest servlet) {
            servletRequest = servlet.getRequest();
            servletResponse = servlet.getResponse();
            if (servletResponse != null && servletResponse.isCommitted()) {
                return null;
            }
        }
        String path = servletRequest == null ? null : servletRequest.getRequestURI();
        ProblemDetail problem;
        if (RequestBodyTooLargeException.isCauseOf(ex)) {
            // Jackson reports the aborted stream as an unreadable message; the cause is the size limit.
            statusCode = HttpStatus.PAYLOAD_TOO_LARGE;
            problem = problems.forStatus(statusCode, path);
        } else if (statusCode.is5xxServerError()) {
            problem = serverError(statusCode, ex, servletRequest, servletResponse);
        } else if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
            problem = invalid(ValidationErrors.of(ex), path);
        } else {
            problem = problems.forStatus(statusCode, path);
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        if (headers != null) {
            headers.forEach((name, values) -> {
                if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
                    responseHeaders.addAll(name, values);
                }
            });
        }
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, responseHeaders, statusCode);
    }

    private ProblemDetail invalid(List<FieldViolation> errors, String path) {
        if (path != null && path.startsWith(AUTH_PATH)) {
            return problems.invalid(AUTH_INVALID_REQUEST, "Request body is missing or invalid", errors, path);
        }
        return problems.invalid(Problems.INVALID_REQUEST, "The request is invalid.", errors, path);
    }

    private ProblemDetail serverError(HttpStatusCode status, Exception e, HttpServletRequest request,
                                      HttpServletResponse response) {
        String correlationId = CorrelationIds.of(request, response);
        log.error("Request failed with an unexpected {} (correlationId={})", e.getClass().getName(), correlationId, e);
        return problems.serverError(status, correlationId, request == null ? null : request.getRequestURI());
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, headers, HttpStatusCode.valueOf(problem.getStatus()));
    }

    /** Whole seconds, rounded up, at least 1. */
    static long retryAfterSeconds(Duration duration) {
        long seconds = duration.toSeconds() + (duration.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
