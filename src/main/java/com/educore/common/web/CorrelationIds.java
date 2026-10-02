package com.educore.common.web;

import com.educore.security.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * The correlation id of a failed request, independent of the thread that renders the failure (async and
 * error dispatches run without the request filter's MDC). Lookup order:
 * <ol>
 *   <li>the request attribute {@link #REQUEST_ATTRIBUTE}, which the request-id filter sets once per request
 *       and which survives every dispatch of that request;</li>
 *   <li>the {@code X-Request-Id} response header the filter wrote;</li>
 *   <li>the MDC value of the current thread;</li>
 *   <li>a new UUID, which is then also written as {@code X-Request-Id} so client and log agree.</li>
 * </ol>
 */
public final class CorrelationIds {

    /** Request attribute holding the request id (set by the request-id filter). */
    public static final String REQUEST_ATTRIBUTE = "com.educore.requestId";

    private CorrelationIds() {
    }

    public static String of(HttpServletRequest request, HttpServletResponse response) {
        if (request != null && request.getAttribute(REQUEST_ATTRIBUTE) instanceof String id && !id.isBlank()) {
            return id;
        }
        if (response != null) {
            String header = response.getHeader(RequestIdFilter.HEADER);
            if (header != null && !header.isBlank()) {
                return header;
            }
        }
        String mdc = MDC.get(RequestIdFilter.MDC_KEY);
        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }
        String generated = UUID.randomUUID().toString();
        if (response != null && !response.isCommitted()) {
            response.setHeader(RequestIdFilter.HEADER, generated);
        }
        if (request != null) {
            request.setAttribute(REQUEST_ATTRIBUTE, generated);
        }
        return generated;
    }
}
