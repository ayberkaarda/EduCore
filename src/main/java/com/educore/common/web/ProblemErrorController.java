package com.educore.common.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Replaces Spring Boot's {@code BasicErrorController}: errors that never reach a controller (the servlet
 * container or the firewall rejected the request, a filter failed, {@code sendError} was called) are also
 * answered as {@code application/problem+json} in the shape of {@link Problems}, whatever the client's
 * {@code Accept} header asks for (no HTML error page exists). A 5xx carries the request's
 * {@code X-Request-Id} as {@code correlationId}, and the failure is logged under it.
 */
@RestController
public class ProblemErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorController.class);

    private final Problems problems;

    public ProblemErrorController(Problems problems) {
        this.problems = problems;
    }

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ProblemDetail> error(HttpServletRequest request, HttpServletResponse response) {
        HttpStatusCode status = status(request);
        String instance = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        ProblemDetail problem;
        if (status.is5xxServerError()) {
            String correlationId = CorrelationIds.of(request, response);
            Object failure = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            if (failure instanceof Throwable throwable) {
                log.error("Request failed outside the controllers with {} (correlationId={})",
                        throwable.getClass().getName(), correlationId, throwable);
            } else {
                log.error("Request failed outside the controllers with status {} (correlationId={})",
                        status.value(), correlationId);
            }
            problem = problems.serverError(status, correlationId, instance);
        } else if (status.value() == HttpStatus.BAD_REQUEST.value()) {
            // Rejected before any field was bound (container or firewall): same code, an empty errors list.
            problem = problems.invalid(Problems.INVALID_REQUEST, "The request is invalid.", List.of(), instance);
        } else {
            problem = problems.forStatus(status, instance);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        // The security header writer does not run on the error dispatch (it is a once-per-request filter and a
        // firewall rejection happens before it), so the error path sets the header itself; setHeader replaces a
        // value the header writer may already have written for the original request.
        response.setHeader(ProblemResponseWriter.NOSNIFF_HEADER, "nosniff");
        return new ResponseEntity<>(problem, headers, status);
    }

    /** The status of the failed request; a direct call of the error path without a failure is a 404. */
    private static HttpStatusCode status(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer value && value >= 400 && value <= 599) {
            return HttpStatusCode.valueOf(value);
        }
        return HttpStatus.NOT_FOUND;
    }
}
