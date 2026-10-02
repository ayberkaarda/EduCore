package com.educore.common.web;

import com.educore.config.EduCoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds every RFC 9457 problem the API answers with, so the advice, the {@code /error} controller and the
 * security entry points share one shape:
 * <pre>{type, title, status, detail, instance, code[, errors][, correlationId][, ...]}</pre>
 * {@code type} is {@code <educore.problems.base-url>/<code>}; {@code title} and {@code detail} are fixed
 * texts per code or status (never exception messages or request values); {@code instance} is the request
 * path. Generic codes for a bare status are listed in {@link #forStatus}.
 */
@Component
public class Problems {

    public static final String INVALID_REQUEST = "request/invalid";
    public static final String NOT_FOUND = "request/not-found";
    public static final String METHOD_NOT_ALLOWED = "request/method-not-allowed";
    public static final String NOT_ACCEPTABLE = "request/not-acceptable";
    public static final String CONFLICT = "request/conflict";
    public static final String CONCURRENT_MODIFICATION = "request/concurrent-modification";
    public static final String PAYLOAD_TOO_LARGE = "request/payload-too-large";
    public static final String UNSUPPORTED_MEDIA_TYPE = "request/unsupported-media-type";
    public static final String REJECTED = "request/rejected";
    public static final String UNAUTHENTICATED = "auth/unauthenticated";
    public static final String ACCESS_DENIED = "auth/access-denied";
    public static final String SORT_INVALID = "sort/invalid";
    public static final String INTERNAL_ERROR = "server/internal-error";

    /** Problem member holding the {@link FieldViolation}s of a 400. */
    public static final String ERRORS = "errors";
    /** Problem member of a 5xx: the request id under which the failure was logged. */
    public static final String CORRELATION_ID = "correlationId";
    /** Problem member holding the stable code (also the last segments of {@code type}). */
    public static final String CODE = "code";

    private static final Map<Integer, Generic> GENERIC = Map.of(
            400, new Generic(INVALID_REQUEST, "The request is invalid."),
            401, new Generic(UNAUTHENTICATED, "Authentication is required."),
            403, new Generic(ACCESS_DENIED, "Access is denied."),
            404, new Generic(NOT_FOUND, "The requested resource was not found."),
            405, new Generic(METHOD_NOT_ALLOWED, "The request method is not supported by this resource."),
            406, new Generic(NOT_ACCEPTABLE, "The requested representation is not available."),
            409, new Generic(CONFLICT, "The request conflicts with the current state of the data."),
            413, new Generic(PAYLOAD_TOO_LARGE, "The request payload is too large."),
            415, new Generic(UNSUPPORTED_MEDIA_TYPE, "The request content type is not supported."));

    private static final Map<Integer, String> DETAILS = Map.ofEntries(
            Map.entry(400, "One or more request values are missing or invalid."),
            Map.entry(401, "Sign in or refresh the session, then retry."),
            Map.entry(403, "The authenticated account is not allowed to perform this request."),
            Map.entry(404, "No resource exists at this address."),
            Map.entry(405, "Use one of the methods listed in the Allow header."),
            Map.entry(406, "The API answers with JSON only."),
            Map.entry(409, "Reload the data and retry if the change is still wanted."),
            Map.entry(413, "Send a smaller payload."),
            Map.entry(415, "Send the body as application/json."),
            Map.entry(423, "Wait for the time given in the Retry-After header, then retry."),
            Map.entry(429, "Wait for the time given in the Retry-After header, then retry."),
            Map.entry(503, "The service is temporarily unavailable; retry later."));

    private static final String CLIENT_DETAIL = "The request was not processed.";
    private static final String SERVER_DETAIL = "The failure was recorded under the given correlationId.";

    private final String base;

    public Problems(EduCoreProperties properties) {
        String configured = properties.problems().baseUrl().toString();
        this.base = configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
    }

    /** A problem with a specific code and fixed title. */
    public ProblemDetail create(HttpStatusCode status, String code, String title, String instance) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(base + "/" + code));
        problem.setTitle(title);
        problem.setDetail(detailFor(status.value()));
        if (instance != null && !instance.isEmpty()) {
            problem.setInstance(URI.create(safePath(instance)));
        }
        problem.setProperty(CODE, code);
        return problem;
    }

    /**
     * The generic problem for a bare status: 400 {@code request/invalid}, 401 {@code auth/unauthenticated},
     * 403 {@code auth/access-denied}, 404 {@code request/not-found}, 405 {@code request/method-not-allowed},
     * 406 {@code request/not-acceptable}, 409 {@code request/conflict}, 413 {@code request/payload-too-large},
     * 415 {@code request/unsupported-media-type}, any other 4xx {@code request/rejected} and any 5xx
     * {@code server/internal-error} (use {@link #serverError} to attach the correlation id).
     */
    public ProblemDetail forStatus(HttpStatusCode status, String instance) {
        Generic generic = GENERIC.get(status.value());
        if (generic != null) {
            return create(status, generic.code(), generic.title(), instance);
        }
        if (status.is5xxServerError()) {
            return create(status, INTERNAL_ERROR, "An unexpected error occurred.", instance);
        }
        return create(status, REJECTED, "The request was rejected.", instance);
    }

    /** A 400 {@code request/invalid} (or the given code) listing the invalid fields. */
    public ProblemDetail invalid(String code, String title, List<FieldViolation> errors, String instance) {
        ProblemDetail problem = create(HttpStatus.BAD_REQUEST, code, title, instance);
        problem.setProperty(ERRORS, List.copyOf(errors));
        return problem;
    }

    /** A 5xx problem that carries only the correlation id of the logged failure. */
    public ProblemDetail serverError(HttpStatusCode status, String correlationId, String instance) {
        ProblemDetail problem = forStatus(status.is5xxServerError() ? status : HttpStatus.INTERNAL_SERVER_ERROR,
                instance);
        problem.setProperty(CORRELATION_ID, correlationId);
        return problem;
    }

    /** The members of {@code problem} in a stable order, for writers that do not go through Spring MVC. */
    public static Map<String, Object> toMap(ProblemDetail problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        if (problem.getDetail() != null) {
            body.put("detail", problem.getDetail());
        }
        if (problem.getInstance() != null) {
            body.put("instance", problem.getInstance().toString());
        }
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        return body;
    }

    static String detailFor(int status) {
        String detail = DETAILS.get(status);
        if (detail != null) {
            return detail;
        }
        return status >= 500 ? SERVER_DETAIL : CLIENT_DETAIL;
    }

    /**
     * The request path as a relative URI reference. Characters that are not legal in a URI path (the raw path
     * can contain anything a client sent) are dropped, so building the problem can never fail.
     */
    static String safePath(String path) {
        StringBuilder safe = new StringBuilder(Math.min(path.length(), 512));
        for (int i = 0; i < path.length() && safe.length() < 512; i++) {
            char c = path.charAt(i);
            if (c == '%') {
                if (i + 2 < path.length() && isHex(path.charAt(i + 1)) && isHex(path.charAt(i + 2))) {
                    safe.append(path, i, i + 3);
                    i += 2;
                }
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "/-._~!$&'()*+,;=:@".indexOf(c) >= 0) {
                safe.append(c);
            }
        }
        return safe.isEmpty() ? "/" : safe.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private record Generic(String code, String title) {
    }
}
