package com.educore.security;

import com.educore.common.web.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.io.IOException;

/**
 * Spring's CORS checks with the API's error shape: a rejected cross-origin request (origin, method or header
 * not allowed) is answered 403 {@code request/cors-rejected} as {@code application/problem+json} instead of
 * the plain-text {@code Invalid CORS request}. No {@code Access-Control-Allow-*} header is written for it.
 */
public class ProblemCorsProcessor extends DefaultCorsProcessor {

    public static final String CODE = "request/cors-rejected";
    static final String TITLE = "The request origin is not allowed.";

    private final ProblemResponseWriter writer;

    public ProblemCorsProcessor(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public boolean processRequest(CorsConfiguration config, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        boolean allowed = super.processRequest(config, request, response);
        if (!allowed) {
            writer.write(request, response, HttpStatus.FORBIDDEN, CODE, TITLE);
        }
        return allowed;
    }

    /** The body is written by {@link #processRequest}, which knows the request path. */
    @Override
    protected void rejectRequest(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
    }
}
