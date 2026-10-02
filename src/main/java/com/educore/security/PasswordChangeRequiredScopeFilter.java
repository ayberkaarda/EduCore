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
 * Server-side enforcement of {@code mustChangePassword} (R-03): an account created with a temporary password
 * (API-created and CSV-imported students) or by the bootstrap ({@code EDUCORE_BOOTSTRAP_ADMIN_*}) holds a full
 * credential only after it chose its own password. Runs right after {@link JwtAuthenticationFilter} in both
 * security chains; when the request is authenticated with the
 * {@link AccessTokenAuthentication#isPasswordChangeRequired() password-change scope}, only these requests pass:
 * <ul>
 *   <li>{@code GET /api/v1/auth/me} (the signed-in user, {@code mustChangePassword: true}),</li>
 *   <li>{@code POST /api/v1/auth/password} (sets the new password; the answer is a full session),</li>
 *   <li>{@code POST /api/v1/auth/refresh} and {@code POST /api/v1/auth/logout} (anonymous by URL rule anyway).</li>
 * </ul>
 * Every other request, on the application port (including unknown paths and path variants such as a trailing
 * slash) and on the management port, is answered 403 {@code account/password-change-required}. Paths are compared
 * exactly after the context path. CORS preflights are not affected.
 * <p>
 * Not a Spring bean: {@code SecurityConfig} adds one instance to each chain.
 */
public class PasswordChangeRequiredScopeFilter extends OncePerRequestFilter {

    public static final String CODE = "account/password-change-required";
    static final String TITLE = "The password must be changed before the account can be used.";

    static final Set<String> ALLOWED = Set.of(
            "GET /api/v1/auth/me",
            "POST /api/v1/auth/password",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout");

    private final ProblemResponseWriter problemWriter;

    public PasswordChangeRequiredScopeFilter(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AccessTokenAuthentication token && token.isPasswordChangeRequired()
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
