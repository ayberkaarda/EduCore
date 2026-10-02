package com.educore.common.web;

import java.io.IOException;

/**
 * Thrown by the stream of {@link RequestBodyLimitFilter} when a request body grows past the limit. It is an
 * {@link IOException} so that body readers (Jackson) abort; {@link ProblemDetailsAdvice} answers 413.
 */
public class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException() {
        super("Request body exceeds the configured limit", null);
    }

    /** Whether {@code throwable} or one of its causes is this exception. */
    public static boolean isCauseOf(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof RequestBodyTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
