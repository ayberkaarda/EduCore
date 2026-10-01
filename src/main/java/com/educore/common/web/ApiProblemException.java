package com.educore.common.web;

import org.springframework.http.HttpStatus;

/**
 * A client-facing failure of a feature endpoint, rendered by {@link ApiExceptionHandler} as
 * {@code application/problem+json} with {@code type = <educore.problems.base-url>/<code>} and a fixed
 * {@code title}. Titles are constant texts: they never echo request input or exception messages.
 */
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;

    public ApiProblemException(HttpStatus status, String code, String title) {
        super(code);
        this.status = status;
        this.code = code;
        this.title = title;
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
}
