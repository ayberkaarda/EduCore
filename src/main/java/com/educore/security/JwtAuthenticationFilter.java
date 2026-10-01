package com.educore.security;

import com.educore.entity.Account;
import com.educore.repository.AccountRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates requests carrying {@code Authorization: Bearer <access token>}.
 * <p>
 * The token's {@code sub} is the account id; the account is loaded on every request so a changed role, a
 * removed account or a soft-deleted account ({@link ActiveAccount}) takes effect immediately. The principal
 * is the typed {@link AuthenticatedUser} built from that account; the token's {@code roles} claim is ignored. Any token
 * problem ({@link JwtException} or {@link IllegalArgumentException}) leaves the request unauthenticated
 * and lets it continue: protected endpoints then answer 401, never 500.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AccountRepository accountRepository;

    public JwtAuthenticationFilter(JwtService jwtService, AccountRepository accountRepository) {
        this.jwtService = jwtService;
        this.accountRepository = accountRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request, header.substring(BEARER_PREFIX.length()).trim());
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            JwtService.AccessTokenClaims claims = jwtService.parse(token);
            Account account = accountRepository.findById(claims.accountId()).orElse(null);
            if (!ActiveAccount.isActive(account)) {
                return;
            }
            AccessTokenAuthentication authentication = new AccessTokenAuthentication(AuthenticatedUser.of(account));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            // Only the exception type: messages can quote token content.
            log.debug("Bearer token rejected: {}", e.getClass().getSimpleName());
        }
    }
}
