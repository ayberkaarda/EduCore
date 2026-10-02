package com.educore.common.logging;

/**
 * MDC keys written by the web filters and emitted with every log line: as top-level members of the JSON
 * log event (structured format) and in the {@code [requestId,userId]} prefix of the human-readable pattern.
 */
public final class MdcKeys {

    /** Id of the current HTTP request ({@code X-Request-Id}), set by {@code RequestIdFilter}. */
    public static final String REQUEST_ID = "requestId";

    /** Account id of the authenticated caller, set by {@code JwtAuthenticationFilter} after authentication. */
    public static final String USER_ID = "userId";

    private MdcKeys() {
    }
}
