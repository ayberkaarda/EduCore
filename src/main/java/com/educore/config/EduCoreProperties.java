package com.educore.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Typed, validated application settings bound from {@code educore.*}.
 * <p>
 * Values with a sensible default carry it here; values that must come from the environment
 * (JWT secret, bootstrap admin in {@code prod}) are validated at startup so a misconfigured
 * deployment fails before it serves a request. Records holding secrets override
 * {@code toString()} so the values never reach logs.
 */
@Validated
@ConfigurationProperties("educore")
public record EduCoreProperties(
        @Valid @NotNull @DefaultValue Security security,
        @Valid @NotNull @DefaultValue Cors cors,
        @Valid @NotNull @DefaultValue IpAccess ipaccess,
        @Valid @NotNull @DefaultValue Ingestion ingestion,
        @Valid @NotNull @DefaultValue Webhook webhook,
        @Valid @NotNull @DefaultValue Seo seo,
        @Valid @NotNull @DefaultValue Problems problems,
        @Valid @NotNull @DefaultValue Bootstrap bootstrap) {

    /** {@code educore.security.*} */
    public record Security(
            @Valid @NotNull @DefaultValue Jwt jwt,
            @Valid @NotNull @DefaultValue Login login,
            @Valid @NotNull @DefaultValue RefreshToken refreshToken) {
    }

    /**
     * {@code educore.security.jwt.*}. The current signing secret is supplied through
     * {@code EDUCORE_JWT_SECRET} and the optional previous one (accepted for verification only, during a
     * key rotation) through {@code EDUCORE_JWT_SECRET_PREVIOUS}; both are base64 of at least 32 decoded
     * bytes, validated by {@link com.educore.security.JwtService} with a message naming the variable.
     */
    public record Jwt(
            String secret,
            String previousSecret,
            @NotBlank @DefaultValue("educore") String issuer,
            @NotBlank @DefaultValue("educore-api") String audience,
            @NotNull @DefaultValue("15m") Duration accessTokenTtl) {

        @Override
        public String toString() {
            return "Jwt[secret=" + redact(secret) + ", previousSecret=" + redact(previousSecret)
                    + ", issuer=" + issuer + ", audience=" + audience + ", accessTokenTtl=" + accessTokenTtl + "]";
        }
    }

    /**
     * {@code educore.security.login.*}: login throttling and lockout. {@code usernamePepper}
     * ({@code EDUCORE_LOGIN_PEPPER}) keys the HMAC-SHA-256 under which usernames are stored in
     * {@code login_attempt}; it is required in {@code prod} (see {@link ProdStartupGuard}).
     */
    public record Login(
            String usernamePepper,
            @Positive @DefaultValue("10") int ipAttemptsPerMinute,
            @Positive @DefaultValue("5") int maxFailures,
            @NotNull @DefaultValue("15m") Duration lockDuration) {

        @Override
        public String toString() {
            return "Login[usernamePepper=" + redact(usernamePepper) + ", ipAttemptsPerMinute=" + ipAttemptsPerMinute
                    + ", maxFailures=" + maxFailures + ", lockDuration=" + lockDuration + "]";
        }
    }

    /**
     * {@code educore.security.refresh-token.*}: lifetime of the opaque refresh token and whether its cookie
     * carries the {@code Secure} attribute ({@code false} only in the {@code dev} profile, which runs on plain
     * HTTP on localhost).
     */
    public record RefreshToken(
            @NotNull @DefaultValue("14d") Duration ttl,
            @DefaultValue("true") boolean cookieSecure) {
    }

    private static String redact(String value) {
        return value == null || value.isBlank() ? "<unset>" : "<redacted>";
    }

    /** {@code educore.cors.*}: origins allowed to call the API cross-origin (comma-separated in the env var). */
    public record Cors(@DefaultValue List<@NotBlank String> allowedOrigins) {
    }

    /** {@code educore.ipaccess.*}: proxies whose {@code X-Forwarded-For} header is trusted. */
    public record IpAccess(@DefaultValue List<@NotBlank String> trustedProxies) {
    }

    /** {@code educore.ingestion.*}: CSV ingestion folder and upload limits. */
    public record Ingestion(
            @NotNull @DefaultValue("csv_uploads") Path baseDir,
            @NotNull @DefaultValue("20MB") DataSize maxBytes,
            @Positive @DefaultValue("50000") int maxRows) {
    }

    /** {@code educore.webhook.*}: outbound webhook delivery limits. */
    public record Webhook(
            @NotNull @DefaultValue("5s") Duration connectTimeout,
            @NotNull @DefaultValue("5s") Duration readTimeout,
            @Positive @DefaultValue("5") int maxAttempts) {
    }

    /** {@code educore.seo.*}: public site origin and AI crawler policy. */
    public record Seo(
            @NotNull @DefaultValue("http://localhost:3000") URI baseUrl,
            @Valid @NotNull @DefaultValue AiCrawlers aiCrawlers) {
    }

    /** {@code educore.seo.ai-crawlers.*}: whether the listed AI user agents may read public paths. */
    public record AiCrawlers(
            @DefaultValue("true") boolean allowPublic,
            @DefaultValue({"GPTBot", "ClaudeBot", "Claude-SearchBot", "PerplexityBot", "Google-Extended", "CCBot"})
            List<@NotBlank String> userAgents) {
    }

    /** {@code educore.problems.*}: base of RFC 9457 problem {@code type} URIs. */
    public record Problems(@NotNull @DefaultValue("/problems") URI baseUrl) {
    }

    /** {@code educore.bootstrap.*} */
    public record Bootstrap(@Valid @NotNull @DefaultValue Admin admin) {
    }

    /**
     * {@code educore.bootstrap.admin.*}: first ADMIN created at startup when no ADMIN exists.
     * Optional outside {@code prod}; required in {@code prod} (see {@link ProdStartupGuard}).
     */
    public record Admin(String username, String password) {

        static final int MIN_PASSWORD_LENGTH = 12;

        public boolean isConfigured() {
            return hasText(username) && hasText(password);
        }

        @AssertTrue(message = "EDUCORE_BOOTSTRAP_ADMIN_USERNAME and EDUCORE_BOOTSTRAP_ADMIN_PASSWORD "
                + "must be set together or not at all")
        public boolean isCompletePair() {
            return hasText(username) == hasText(password);
        }

        @AssertTrue(message = "EDUCORE_BOOTSTRAP_ADMIN_PASSWORD must be at least "
                + MIN_PASSWORD_LENGTH + " characters long")
        public boolean isPasswordLengthValid() {
            return !hasText(password) || password.length() >= MIN_PASSWORD_LENGTH;
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        @Override
        public String toString() {
            return "Admin[username=" + username + ", password="
                    + (password == null || password.isBlank() ? "<unset>" : "<redacted>") + "]";
        }
    }
}
