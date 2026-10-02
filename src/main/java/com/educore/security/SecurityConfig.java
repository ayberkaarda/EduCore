package com.educore.security;

import com.educore.common.web.ProblemResponseWriter;
import com.educore.config.EduCoreProperties;
import com.educore.ipaccess.IpAccessControlFilter;
import com.educore.ipaccess.IpAutoDenyService;
import com.educore.ipaccess.IpDenyRuleCache;
import com.educore.ratelimit.RateLimitFilter;
import com.educore.ratelimit.RateLimitStore;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.channel.SecureChannelProcessor;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.Map;

/**
 * Authorization is enforced in two layers that both implement {@code docs/security/RBAC_MATRIX.md}: the URL
 * rules below and method security ({@code @PreAuthorize} on admin controllers and services, ownership checks
 * on enrollment services) enabled by {@link EnableMethodSecurity}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    static final int BCRYPT_STRENGTH = 12;
    static final String BCRYPT_ID = "bcrypt";

    @Autowired
    private JwtAuthenticationFilter jwtAuthFilter;

    @Autowired
    private EduCoreProperties properties;

    @Autowired
    private ProblemSecurityHandlers problemHandlers;

    @Autowired
    private ProblemResponseWriter problemResponseWriter;

    @Autowired
    private ClientIpResolver clientIpResolver;

    @Autowired
    private IpDenyRuleCache ipDenyRuleCache;

    @Autowired
    private IpAutoDenyService ipAutoDenyService;

    @Autowired
    private RateLimitStore rateLimitStore;

    @Autowired
    private JwtService jwtService;

    /** CORS preflight results may be cached by browsers for this many seconds. */
    static final long CORS_MAX_AGE_SECONDS = 600;
    static final List<String> CORS_METHODS = List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS");
    /** Anonymous GET and HEAD: public API, sitemap and sitemap files. */
    static final String[] PUBLIC_READ_PATHS = {"/api/v1/public/**", "/sitemap.xml", "/sitemap-courses-*.xml"};
    static final List<String> CORS_HEADERS = List.of("Authorization", "Content-Type", "Accept", "Accept-Language",
            "If-None-Match", RequestIdFilter.HEADER);
    static final List<String> CORS_EXPOSED_HEADERS = List.of("Retry-After", "ETag", RequestIdFilter.HEADER);
    /** The only path whose cross-origin requests may carry cookies: the path of the refresh cookie. */
    static final String CREDENTIALED_CORS_PATH = "/api/v1/auth/**";

    /**
     * Actuator endpoints, served only on the management port ({@code management.server.port}).
     * {@code health} is open for probes; every other exposed endpoint requires an ADMIN token.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .cors(cors -> cors.disable())
                .addFilterAt(problemCorsFilter(), CorsFilter.class)
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .anyRequest().hasRole("ADMIN")
                )
                // Anonymous callers get 401 (same as the application chain), authenticated non-admins 403;
                // both as problem+json.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problemHandlers).accessDeniedHandler(problemHandlers))
                // Same header policy as the application port; no HTTPS requirement (internal port, not published).
                .headers(headers -> SecurityHeaders.apply(headers, false))
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // An account that must change its password gets 403 account/password-change-required here too.
                .addFilterAfter(new PasswordChangeRequiredScopeFilter(problemResponseWriter),
                        JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Application endpoints. Request path (docs/security/HEADERS.md): HTTPS requirement (prod), security
     * headers, {@link IpAccessControlFilter}, CORS, {@link RateLimitFilter}, {@link JwtAuthenticationFilter},
     * then URL rules and method security. CSRF tokens are disabled because every endpoint except
     * {@code /api/v1/auth/refresh} and {@code /api/v1/auth/logout} authenticates with the
     * {@code Authorization: Bearer} header, which browsers never attach automatically. The two cookie-based
     * endpoints are protected by {@code SameSite=Strict} plus the {@link OriginVerifier} allow-list check.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Spring's CORS checks with a problem+json 403 for rejected origins (ProblemCorsProcessor);
                // the default CORS configurer is disabled so only this filter runs.
                .cors(cors -> cors.disable())
                .addFilterAt(problemCorsFilter(), CorsFilter.class)
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // An async dispatch continues a request that already passed authorization (the
                        // bearer filter runs once per request, so the dispatch itself carries no token).
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // Browser CORS preflight requests carry no credentials.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Spring Boot's error path must stay reachable so 404s are not turned into 401s.
                        .requestMatchers("/error").permitAll()

                        // Anonymous: obtaining and ending a session (logout is authenticated by the refresh
                        // cookie plus the Origin check, so an expired access token must not block it).
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        // Anonymous, read-only: the public API and the sitemap files (publicapi package).
                        .requestMatchers(HttpMethod.GET, PUBLIC_READ_PATHS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, PUBLIC_READ_PATHS).permitAll()

                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                        // Everything else (own profile, enrollments, catalog, weather, unknown paths) needs a
                        // valid access token; there is no /ws/** or other legacy exception.
                        .anyRequest().authenticated()
                )
                // Missing or invalid credentials answer 401 so clients know to refresh or log in again; a
                // missing role answers 403. Both are problem+json (ProblemSecurityHandlers).
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problemHandlers).accessDeniedHandler(problemHandlers))
                // JSON-only API: nosniff, DENY framing, CSP default-src 'none', HSTS in prod (SecurityHeaders).
                .headers(headers -> SecurityHeaders.apply(headers, httpsRequired()))
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // IP deny rules first, then rate limiting, then the bearer token. One instance each and not beans,
                // so they run in this chain only and are not also registered as servlet filters.
                // The IP filter runs before CORS so that preflights from denied addresses are refused as well;
                // rate limiting runs after CORS, so preflights (answered by the CORS filter) are not counted.
                .addFilterBefore(new IpAccessControlFilter(clientIpResolver, ipDenyRuleCache, problemResponseWriter,
                        properties.ipaccess().ipv6Policy(), ipAutoDenyService), CorsFilter.class)
                .addFilterAfter(new RateLimitFilter(properties, rateLimitStore, clientIpResolver, jwtService,
                        problemResponseWriter), CorsFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // An account in its deletion grace period may only read its profile, restore itself or log out
                // (403 account/pending-deletion otherwise); runs before the URL rules and method security.
                .addFilterAfter(new PendingDeletionScopeFilter(problemResponseWriter), JwtAuthenticationFilter.class)
                // An account with mustChangePassword may only read itself, change the password, refresh or log
                // out (403 account/password-change-required otherwise); also before the URL rules.
                .addFilterAfter(new PasswordChangeRequiredScopeFilter(problemResponseWriter),
                        PendingDeletionScopeFilter.class);

        if (httpsRequired()) {
            // isSecure() is true for TLS on this server or for X-Forwarded-Proto: https sent by a trusted proxy
            // (RemoteIpValve narrowed by ForwardedHeadersConfig). Plain HTTP answers 403 request/https-required.
            SecureChannelProcessor secure = new SecureChannelProcessor();
            secure.setEntryPoint(SecurityHeaders.httpsRequiredEntryPoint(problemResponseWriter));
            http.requiresChannel(channel -> channel
                    .channelProcessors(List.of(secure))
                    .anyRequest().requiresSecure());
        }

        return http.build();
    }

    private boolean httpsRequired() {
        return properties.security().https().required();
    }

    /**
     * {@code DelegatingPasswordEncoder} with bcrypt strength 12 as the default ({@code {bcrypt}} prefix).
     * Legacy hashes without a prefix (plain bcrypt from earlier versions and the dev seed) still match and
     * report {@code upgradeEncoding == true}, so they are re-hashed on the next successful login.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(BCRYPT_STRENGTH);
        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder(BCRYPT_ID, Map.of(BCRYPT_ID, bcrypt));
        encoder.setDefaultPasswordEncoderForMatches(bcrypt);
        return encoder;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /** A new filter instance per chain (not a bean, so it is not also registered as a servlet filter). */
    private CorsFilter problemCorsFilter() {
        CorsFilter filter = new CorsFilter(corsConfigurationSource());
        filter.setCorsProcessor(new ProblemCorsProcessor(problemResponseWriter));
        return filter;
    }

    /**
     * The single CORS policy of the API (no {@code @CrossOrigin} anywhere). Allowed origins come from
     * {@code educore.cors.allowed-origins} (env {@code EDUCORE_CORS_ALLOWED_ORIGINS}; empty in prod, where the SPA
     * and the API share one origin). Methods and request headers are explicit lists; preflights may be cached
     * for {@value #CORS_MAX_AGE_SECONDS} s. Credentials (the refresh cookie) are allowed only under
     * {@code /api/v1/auth/**}; every other route authenticates with the bearer header and answers cross-origin
     * requests without {@code Access-Control-Allow-Credentials}.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // First match wins: the credentialed auth path is registered before the catch-all.
        source.registerCorsConfiguration(CREDENTIALED_CORS_PATH, corsConfiguration(true));
        source.registerCorsConfiguration("/**", corsConfiguration(false));
        return source;
    }

    private CorsConfiguration corsConfiguration(boolean allowCredentials) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.copyOf(properties.cors().allowedOrigins()));
        configuration.setAllowedMethods(CORS_METHODS);
        configuration.setAllowedHeaders(CORS_HEADERS);
        configuration.setExposedHeaders(CORS_EXPOSED_HEADERS);
        configuration.setAllowCredentials(allowCredentials);
        configuration.setMaxAge(CORS_MAX_AGE_SECONDS);
        return configuration;
    }
}
