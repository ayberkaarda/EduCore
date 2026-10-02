package com.educore.ipaccess;

import com.educore.common.web.ProblemResponseWriter;
import com.educore.config.EduCoreProperties;
import com.educore.security.ClientIpResolver;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * First stage of the application request path, before CORS (so preflights from denied addresses are refused
 * too), rate limiting and token authentication. It resolves the client address ({@link ClientIpResolver},
 * trusted-proxy aware, IPv4-mapped IPv6 normalised) and checks the cached deny rules ({@link IpDenyRuleCache},
 * no database query per request):
 * <ul>
 *   <li>a MANUAL rule covers the address: every request is answered 403 {@code ipaccess/denied};</li>
 *   <li>only AUTO rules (written after repeated failed logins) cover it: only {@code POST /api/v1/auth/login}
 *       is refused, so users sharing the address behind NAT keep their sessions and the admin API;</li>
 *   <li>native IPv6 clients: no persisted rule applies; with {@code educore.ipaccess.ipv6-policy=DENY} they are
 *       answered 403 {@code ipaccess/ipv6-unsupported}; otherwise only an automatic denial of their /64
 *       ({@link IpAutoDenyService#isLoginDenied}) refuses {@code POST /api/v1/auth/login};</li>
 *   <li>the rules cannot be loaded and no earlier snapshot exists: 503 {@code ipaccess/unavailable};</li>
 *   <li>a trusted proxy forwarded a client address that is not an IP address: 400
 *       {@code ipaccess/invalid-client-address}.</li>
 * </ul>
 * The response names no rule, range, reason or expiry. A denied request is logged as {@code IP_DENIED} (the
 * log pipeline masks the last octet) at most once per address and minute; no audit event per request.
 * <p>
 * Not a Spring bean: {@code SecurityConfig} adds one instance to the application filter chain only. The
 * management port is not filtered.
 */
public class IpAccessControlFilter extends OncePerRequestFilter {

    public static final String CODE = "ipaccess/denied";
    public static final String IPV6_CODE = "ipaccess/ipv6-unsupported";
    public static final String UNAVAILABLE_CODE = "ipaccess/unavailable";
    public static final String INVALID_CODE = "ipaccess/invalid-client-address";
    static final String INVALID_TITLE = "The forwarded client address is not an IP address.";
    static final String TITLE = "Requests from this network address are not accepted.";
    static final String IPV6_TITLE = "Requests from IPv6 addresses are not accepted.";
    static final String UNAVAILABLE_TITLE = "The service is temporarily unavailable.";
    static final String LOGIN_PATH = "/api/v1/auth/login";

    private static final Logger log = LoggerFactory.getLogger(IpAccessControlFilter.class);

    private final ClientIpResolver clientIpResolver;
    private final IpDenyRuleCache denyRules;
    private final ProblemResponseWriter problemWriter;
    private final EduCoreProperties.Ipv6Policy ipv6Policy;
    private final IpAutoDenyService autoDeny;
    private final Cache<String, Boolean> recentlyLogged = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    public IpAccessControlFilter(ClientIpResolver clientIpResolver, IpDenyRuleCache denyRules,
                                 ProblemResponseWriter problemWriter, EduCoreProperties.Ipv6Policy ipv6Policy,
                                 IpAutoDenyService autoDeny) {
        this.clientIpResolver = clientIpResolver;
        this.denyRules = denyRules;
        this.problemWriter = problemWriter;
        this.ipv6Policy = ipv6Policy;
        this.autoDeny = autoDeny;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ClientAddress client = clientIpResolver.resolveAddress(request);
        if (!client.isValid()) {
            // Only possible when a trusted proxy forwards a malformed X-Forwarded-For hop: no rule could be checked.
            problemWriter.write(request, response, HttpStatus.BAD_REQUEST, INVALID_CODE, INVALID_TITLE);
            return;
        }
        Optional<Ipv4> address = client.ipv4();
        if (address.isEmpty()) {
            if (client.isIpv6() && ipv6Policy == EduCoreProperties.Ipv6Policy.DENY) {
                problemWriter.write(request, response, HttpStatus.FORBIDDEN, IPV6_CODE, IPV6_TITLE);
                return;
            }
            if (isLogin(request) && autoDeny.isLoginDenied(client)) {
                deny(request, response, client.rateLimitKey(), IpDenyRuleCache.Denial.LOGIN);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }
        IpDenyRuleCache.Denial denial;
        try {
            denial = denyRules.denial(address.get());
        } catch (IpDenyRuleCache.UnavailableException e) {
            log.error("IP deny rules unavailable; request refused (fail closed): {}",
                    e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
            problemWriter.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_CODE,
                    UNAVAILABLE_TITLE);
            return;
        }
        if (denial == IpDenyRuleCache.Denial.ALL
                || (denial == IpDenyRuleCache.Denial.LOGIN && isLogin(request))) {
            deny(request, response, client.canonical(), denial);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void deny(HttpServletRequest request, HttpServletResponse response, String text,
                      IpDenyRuleCache.Denial denial) throws IOException {
        if (recentlyLogged.asMap().putIfAbsent(text, Boolean.TRUE) == null) {
            log.warn("IP_DENIED ip={} scope={}", text, denial);
        }
        problemWriter.write(request, response, HttpStatus.FORBIDDEN, CODE, TITLE);
    }

    private static boolean isLogin(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
                && LOGIN_PATH.equals(request.getRequestURI().substring(request.getContextPath().length()));
    }
}
