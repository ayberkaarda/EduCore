package com.educore.security;

import com.educore.common.web.ProblemResponseWriter;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.access.channel.ChannelEntryPoint;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Response security headers of the API (both filter chains) and the HTTPS requirement. The values are the
 * contract mirrored by the reverse proxy configuration; {@code docs/security/HEADERS.md} lists them together
 * with the SPA's Content-Security-Policy.
 */
public final class SecurityHeaders {

    /** The API returns JSON only: nothing may load, run or frame it. */
    public static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";
    public static final String PERMISSIONS_POLICY = "accelerometer=(), camera=(), geolocation=(), gyroscope=(), "
            + "magnetometer=(), microphone=(), payment=(), usb=()";
    /** Two years; sent only over HTTPS and only when {@code educore.security.https.required} is set. */
    public static final long HSTS_MAX_AGE_SECONDS = 63_072_000;
    public static final String HSTS = "max-age=" + HSTS_MAX_AGE_SECONDS + " ; includeSubDomains ; preload";

    public static final String HTTPS_REQUIRED = "request/https-required";
    static final String HTTPS_REQUIRED_TITLE = "HTTPS is required.";

    private SecurityHeaders() {
    }

    /**
     * Spring Security's defaults (nosniff, {@code Cache-Control: no-cache, no-store}, {@code X-XSS-Protection: 0})
     * plus the policy above; HSTS only when HTTPS is required (Spring writes it on secure requests only).
     */
    static void apply(HeadersConfigurer<HttpSecurity> headers, boolean httpsRequired) {
        headers.contentTypeOptions(options -> { })
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                .contentSecurityPolicy(csp -> csp.policyDirectives(API_CSP))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                .crossOriginOpenerPolicy(coop -> coop.policy(
                        CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN));
        if (httpsRequired) {
            headers.httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)
                    .includeSubDomains(true)
                    .preload(true));
        } else {
            headers.httpStrictTransportSecurity(HeadersConfigurer.HstsConfig::disable);
        }
    }

    /**
     * Answers a plain-HTTP request with 403 {@code request/https-required} instead of a redirect: an API
     * client must not have its method, body and {@code Authorization} header replayed by a redirect, and
     * browsers are redirected to HTTPS by the reverse proxy before they reach the API.
     */
    static ChannelEntryPoint httpsRequiredEntryPoint(ProblemResponseWriter writer) {
        return (request, response) -> {
            // This answer is written before Spring Security's header writer runs: set the policy here too.
            writeNonHstsHeaders(response);
            writer.write(request, response, HttpStatus.FORBIDDEN, HTTPS_REQUIRED, HTTPS_REQUIRED_TITLE);
        };
    }

    /** Every header of {@link #apply} except HSTS (and Spring's cache defaults), for responses written early. */
    static void writeNonHstsHeaders(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", API_CSP);
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
    }
}
