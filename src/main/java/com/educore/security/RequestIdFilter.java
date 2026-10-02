package com.educore.security;

import com.educore.common.logging.MdcKeys;
import com.educore.common.web.CorrelationIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request an id: the incoming {@code X-Request-Id} when it is 1 to 64 characters of
 * {@code [A-Za-z0-9._-]}, otherwise (absent, too long, CR/LF or any other character) a new UUID. The id is put
 * into the MDC as {@code requestId} (every log line, security events) and echoed in the {@code X-Request-Id}
 * response header, and stored in the request attribute {@link CorrelationIds#REQUEST_ATTRIBUTE}. Runs before
 * every other filter.
 * <p>
 * The filter also runs on the container's error dispatch: there it keeps the id already assigned to the
 * request, so a failure handled by the error controller is logged under the same id the client received.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = MdcKeys.REQUEST_ID;
    static final int MAX_LENGTH = 64;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1," + MAX_LENGTH + "}");

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = idOf(request, response);
        request.setAttribute(CorrelationIds.REQUEST_ATTRIBUTE, requestId);
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    /**
     * The id already assigned to this request (request attribute, then response header: a later dispatch of the
     * same request), else the incoming header when safe, else a new UUID.
     */
    private static String idOf(HttpServletRequest request, HttpServletResponse response) {
        if (request.getAttribute(CorrelationIds.REQUEST_ATTRIBUTE) instanceof String assigned && isSafe(assigned)) {
            return assigned;
        }
        String alreadySent = response.getHeader(HEADER);
        return isSafe(alreadySent) ? alreadySent : accepted(request.getHeader(HEADER));
    }

    /** {@code incoming} when it is a safe id, otherwise a new random UUID. */
    static String accepted(String incoming) {
        return isSafe(incoming) ? incoming : UUID.randomUUID().toString();
    }

    private static boolean isSafe(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    /** The id of the request being processed on this thread, or {@code null} outside a request. */
    public static String currentRequestId() {
        return MDC.get(MDC_KEY);
    }
}
