package com.educore.security;

import com.educore.common.web.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * The restore-only scope of an account in its deletion grace period. Runs right after
 * {@link JwtAuthenticationFilter}; when the request is authenticated as
 * {@link AccessTokenAuthentication#isPendingDeletion() pending deletion}, only these requests pass:
 * <ul>
 *   <li>{@code GET /api/v1/me} (the profile shows {@code status} and {@code deleteAfter}),</li>
 *   <li>{@code POST /api/v1/me/restore} (cancels the deletion),</li>
 *   <li>{@code POST /api/v1/auth/logout} (anonymous by URL rule anyway).</li>
 * </ul>
 * Every other request, including routes that would otherwise answer 404 or 405, is answered 403
 * {@code account/pending-deletion}. Paths are compared exactly (after the context path), so path variants
 * (trailing slash, encoded characters, matrix parameters) are denied too.
 * <p>
 * Not a Spring bean: {@code SecurityConfig} adds one instance to the application filter chain only (the
 * management chain grants nothing to such an account because it has no role authority).
 */
public class PendingDeletionScopeFilter extends OncePerRequestFilter {

    public static final String CODE = "account/pending-deletion";
    static final String TITLE = "The account is scheduled for deletion; only restoring it is allowed.";

    private static final Set<String> ALLOWED = Set.of(
            "GET /api/v1/me",
            "POST /api/v1/me/restore",
            "POST /api/v1/auth/logout");

    private final ProblemResponseWriter problemWriter;

    public PendingDeletionScopeFilter(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AccessTokenAuthentication token && token.isPendingDeletion()
                && !HttpMethod.OPTIONS.matches(request.getMethod()) && !allowed(request)) {
            problemWriter.write(request, response, HttpStatus.FORBIDDEN, CODE, TITLE);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean allowed(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ALLOWED.contains(request.getMethod() + " " + path);
    }
}
