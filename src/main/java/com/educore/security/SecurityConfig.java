package com.educore.security;

import com.educore.config.EduCoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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

    /**
     * Actuator endpoints, served only on the management port ({@code management.server.port}).
     * {@code health} is open for probes; every other exposed endpoint requires an ADMIN token.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .anyRequest().hasRole("ADMIN")
                )
                // Anonymous callers get 401 (same as the application chain), authenticated non-admins 403.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Application endpoints. CSRF tokens are disabled because every endpoint except
     * {@code /api/v1/auth/refresh} and {@code /api/v1/auth/logout} authenticates with the
     * {@code Authorization: Bearer} header, which browsers never attach automatically. The two cookie-based
     * endpoints are protected by {@code SameSite=Strict} plus the {@link OriginVerifier} allow-list check.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // Browser CORS preflight requests carry no credentials.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Spring Boot's error path must stay reachable so 404s are not turned into 401s.
                        .requestMatchers("/error").permitAll()

                        // Anonymous: obtaining and ending a session (logout is authenticated by the refresh
                        // cookie plus the Origin check, so an expired access token must not block it).
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/api/v1/public/**").permitAll()

                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                        // Everything else (own profile, enrollments, catalog, weather, unknown paths) needs a
                        // valid access token; there is no /ws/** or other legacy exception.
                        .anyRequest().authenticated()
                )
                // Missing or invalid credentials answer 401 so clients know to refresh or log in again.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
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

    /** Allowed origins come from {@code educore.cors.allowed-origins} (env {@code EDUCORE_CORS_ALLOWED_ORIGINS}). */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.copyOf(properties.cors().allowedOrigins()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // The SPA sends the refresh cookie with credentials: 'include'; origins are an explicit list.
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("Retry-After", RequestIdFilter.HEADER));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
