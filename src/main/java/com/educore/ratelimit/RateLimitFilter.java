package com.educore.ratelimit;

import com.educore.common.web.ProblemResponseWriter;
import com.educore.config.EduCoreProperties;
import com.educore.security.ClientIpResolver;
import com.educore.security.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Second stage of the application request path, after {@code IpAccessControlFilter} and before
 * {@code JwtAuthenticationFilter}. One token bucket per caller ({@link RateLimitStore}):
 * <ul>
 *   <li>{@code /api/v1/public/**}: {@code educore.ratelimit.public-per-minute} (120) per client IP, with or
 *       without a token;</li>
 *   <li>a request whose {@code Authorization: Bearer} token verifies (signature, issuer, audience, expiry;
 *       no database access): {@code authenticated-per-minute} (300) per account id ({@code sub});</li>
 *   <li>every other request (no token, or a token that does not verify):
 *       {@code anonymous-per-minute} (60) per client IP.</li>
 * </ul>
 * The client IP comes from {@link ClientIpResolver}: {@code X-Forwarded-For} counts only when the direct peer
 * is a trusted proxy, so a client cannot pick a fresh key per request; IPv6 clients are keyed by their /64.
 * {@code POST /api/v1/auth/login} is not
 * counted here: it keeps its own stricter per-IP limit (P2, {@code educore.security.login.ip-attempts-per-minute}).
 * A rejected request is answered 429 {@code rate-limit/exceeded} with {@code Retry-After} (whole seconds, at
 * least 1). The token is verified here instead of reading the authenticated principal so that the limit
 * applies before {@code JwtAuthenticationFilter} loads the account from the database; a forged token only ever
 * lands in the anonymous per-IP bucket.
 * <p>
 * Not a Spring bean: {@code SecurityConfig} adds one instance to the application filter chain only.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String CODE = "rate-limit/exceeded";
    static final String TITLE = "Too many requests.";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PUBLIC_PREFIX = "/api/v1/public/";
    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private final EduCoreProperties.RateLimit settings;
    private final RateLimitStore store;
    private final ClientIpResolver clientIpResolver;
    private final JwtService jwtService;
    private final ProblemResponseWriter problemWriter;

    public RateLimitFilter(EduCoreProperties properties, RateLimitStore store, ClientIpResolver clientIpResolver,
                           JwtService jwtService, ProblemResponseWriter problemWriter) {
        this.settings = properties.ratelimit();
        this.store = store;
        this.clientIpResolver = clientIpResolver;
        this.jwtService = jwtService;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !settings.enabled()
                || (HttpMethod.POST.matches(request.getMethod()) && LOGIN_PATH.equals(pathOf(request)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        RateLimitStore.Decision decision = decide(request);
        if (!decision.allowed()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(decision.retryAfter())));
            problemWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS, CODE, TITLE);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private RateLimitStore.Decision decide(HttpServletRequest request) {
        if (pathOf(request).startsWith(PUBLIC_PREFIX)) {
            return store.tryConsume("public:" + clientKey(request), settings.publicPerMinute());
        }
        Long accountId = verifiedAccountId(request);
        if (accountId != null) {
            return store.tryConsume("account:" + accountId, settings.authenticatedPerMinute());
        }
        return store.tryConsume("anonymous:" + clientKey(request), settings.anonymousPerMinute());
    }

    /** IPv4 address (mapped IPv6 and ports normalised) or IPv6 /64 network of the client. */
    private String clientKey(HttpServletRequest request) {
        return clientIpResolver.resolveAddress(request).rateLimitKey();
    }

    /** The {@code sub} of a verifying bearer token, or {@code null}. */
    private Long verifiedAccountId(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        try {
            return jwtService.parse(header.substring(BEARER_PREFIX.length()).trim()).accountId();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    static long retryAfterSeconds(Duration wait) {
        long millis = Math.max(0, wait.toMillis());
        return Math.max(1, (millis + 999) / 1000);
    }
}
