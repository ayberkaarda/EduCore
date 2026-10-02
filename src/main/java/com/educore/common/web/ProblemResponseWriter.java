package com.educore.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes a generic problem straight to the servlet response, for components that run outside Spring MVC
 * (the security filter chain's entry point and access-denied handler, the CORS rejection and the request
 * body limit). Every problem written here carries {@code X-Content-Type-Options: nosniff}.
 */
@Component
public class ProblemResponseWriter {

    static final String NOSNIFF_HEADER = "X-Content-Type-Options";

    private final Problems problems;
    private final ObjectMapper objectMapper;

    public ProblemResponseWriter(Problems problems, ObjectMapper objectMapper) {
        this.problems = problems;
        this.objectMapper = objectMapper;
    }

    /** Writes the generic problem of {@code status} (see {@link Problems#forStatus}). */
    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status) throws IOException {
        write(response, problems.forStatus(status, request.getRequestURI()));
    }

    /** Writes a problem with a specific code and title. */
    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
                      String title) throws IOException {
        write(response, problems.create(status, code, title, request.getRequestURI()));
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(problem.getStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Some callers run before the security header writer (body limit, CORS rejection): set it here too.
        response.setHeader(NOSNIFF_HEADER, "nosniff");
        objectMapper.writeValue(response.getOutputStream(), Problems.toMap(problem));
    }
}
