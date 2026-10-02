package com.educore.security;

import com.educore.common.logging.MdcKeys;
import com.educore.entity.Account;
import com.educore.repository.AccountRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

/**
 * Authenticates requests carrying {@code Authorization: Bearer <access token>}.
 * <p>
 * The token's {@code sub} is the account id; the account is loaded on every request so a changed role, a
 * removed account or a status change ({@link ActiveAccount}) takes effect immediately. The token's session
 * epoch ({@code sep}) must equal the account's {@code session_epoch}: a deletion request, a soft delete or a
 * restore increments it, which ends every access token issued before at once. An active account gets its role,
 * unless it must change its password first: then it gets only the password-change scope
 * ({@link AccessTokenAuthentication#passwordChangeRequired}, enforced by {@link PasswordChangeRequiredScopeFilter});
 * an account in its deletion grace period gets the restore-only scope
 * ({@link AccessTokenAuthentication#pendingDeletion}, enforced by {@link PendingDeletionScopeFilter}); any
 * other account stays unauthenticated. The principal is the typed {@link AuthenticatedUser} built from that
 * account; the token's {@code roles} claim is ignored. Any token problem ({@link JwtException} or
 * {@link IllegalArgumentException}) leaves the request unauthenticated and lets it continue: protected
 * endpoints then answer 401, never 500.
 * <p>
 * After a successful authentication the account id is put into the MDC as {@code userId} for the rest of the
 * request and removed when the request leaves this filter. The token itself is never logged.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AccountRepository accountRepository;
    private final Clock clock;

    public JwtAuthenticationFilter(JwtService jwtService, AccountRepository accountRepository, Clock clock) {
        this.jwtService = jwtService;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean authenticated = header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null
                && authenticate(request, header.substring(BEARER_PREFIX.length()).trim());
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (authenticated) {
                MDC.remove(MdcKeys.USER_ID);
            }
        }
    }

    /** Authenticates the request and puts the account id into the MDC ({@code userId}); true on success. */
    private boolean authenticate(HttpServletRequest request, String token) {
        try {
            JwtService.AccessTokenClaims claims = jwtService.parse(token);
            Account account = accountRepository.findById(claims.accountId()).orElse(null);
            if (account == null || account.getSessionEpoch() != claims.sessionEpoch()) {
                // Issued before a deletion request, soft delete or restore of the account (or no account).
                log.debug("Bearer token rejected: unknown account or ended session epoch");
                return false;
            }
            AccessTokenAuthentication authentication;
            if (ActiveAccount.isActive(account)) {
                authentication = account.isMustChangePassword()
                        ? AccessTokenAuthentication.passwordChangeRequired(AuthenticatedUser.of(account))
                        : new AccessTokenAuthentication(AuthenticatedUser.of(account));
            } else if (ActiveAccount.inGracePeriod(account, clock.instant())) {
                authentication = AccessTokenAuthentication.pendingDeletion(AuthenticatedUser.of(account));
            } else {
                return false;
            }
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            MDC.put(MdcKeys.USER_ID, String.valueOf(account.getId()));
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            // Only the exception type: messages can quote token content.
            log.debug("Bearer token rejected: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
