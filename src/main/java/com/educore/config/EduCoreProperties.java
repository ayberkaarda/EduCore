package com.educore.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
        @Valid @NotNull @DefaultValue RateLimit ratelimit,
        @Valid @NotNull @DefaultValue Ingestion ingestion,
        @Valid @NotNull @DefaultValue Webhook webhook,
        @Valid @NotNull @DefaultValue Crypto crypto,
        @Valid @NotNull @DefaultValue Seo seo,
        @Valid @NotNull @DefaultValue Problems problems,
        @Valid @NotNull @DefaultValue Bootstrap bootstrap,
        @Valid @NotNull @DefaultValue Database database) {

    /** {@code educore.security.*} */
    public record Security(
            @Valid @NotNull @DefaultValue Jwt jwt,
            @Valid @NotNull @DefaultValue Login login,
            @Valid @NotNull @DefaultValue RefreshToken refreshToken,
            @Valid @NotNull @DefaultValue Https https) {
    }

    /**
     * {@code educore.security.https.*}: when {@code required} (the {@code prod} profile), every application
     * request must arrive over HTTPS (directly, or as {@code X-Forwarded-Proto: https} from a trusted proxy) and
     * responses carry HSTS; plain HTTP answers 403 {@code request/https-required}. The management port is
     * excluded. See {@code docs/security/HEADERS.md}.
     */
    public record Https(@DefaultValue("false") boolean required) {
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
     * {@code login_attempt}; it is required in {@code prod} (see {@link ProdStartupGuard}). {@code maxFailures}
     * failures of one (username, client key) pair within {@code lockDuration} lock that pair for
     * {@code lockDuration}; {@code accountThrottle} slows failures of one username across all clients.
     */
    public record Login(
            String usernamePepper,
            @Positive @DefaultValue("10") int ipAttemptsPerMinute,
            @Positive @DefaultValue("5") int maxFailures,
            @NotNull @DefaultValue("15m") Duration lockDuration,
            @Valid @NotNull @DefaultValue AccountThrottle accountThrottle) {

        @Override
        public String toString() {
            return "Login[usernamePepper=" + redact(usernamePepper) + ", ipAttemptsPerMinute=" + ipAttemptsPerMinute
                    + ", maxFailures=" + maxFailures + ", lockDuration=" + lockDuration
                    + ", accountThrottle=" + accountThrottle + "]";
        }
    }

    /**
     * {@code educore.security.login.account-throttle.*}: progressive delay per username across all clients (a rate
     * limit, never a lock). After {@code freeFailures} consecutive failures within {@code window} (since the last
     * success), the next verification of that username must wait {@code baseDelay * 2^(failures - freeFailures)},
     * at most {@code maxDelay}, after the newest failure; earlier attempts get 429 {@code auth/too-many-attempts}
     * without a password check. A client key that signed in to the account successfully within
     * {@code trustedNetworkAge} is exempt, so the owner on a known network is never slowed by others.
     */
    public record AccountThrottle(
            @PositiveOrZero @DefaultValue("5") int freeFailures,
            @NotNull @DefaultValue("1s") Duration baseDelay,
            @NotNull @DefaultValue("30s") Duration maxDelay,
            @NotNull @DefaultValue("15m") Duration window,
            @NotNull @DefaultValue("30d") Duration trustedNetworkAge) {
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

    /**
     * {@code educore.ipaccess.*}. {@code trustedProxies}: IPv4 addresses or CIDR blocks (e.g. {@code 10.0.0.5},
     * {@code 172.18.0.0/16}) whose {@code X-Forwarded-For} / {@code X-Forwarded-Proto} headers are honoured;
     * an invalid entry fails startup. {@code denyCacheTtl}: how long the cached deny rules are reused before
     * they are reloaded (every admin change invalidates the cache at once). {@code cleanupInterval}: how often
     * expired deny rules are deleted. {@code autoDeny}: the automatic deny rule written after repeated failed
     * logins from one client IP. {@code minTrustedPrefix}: the broadest CIDR block accepted as a trusted proxy
     * (default {@code /8}; {@code 0.0.0.0/0} and anything broader fail startup). {@code ipv6Policy}: what
     * happens to clients with a native IPv6 address, to which no IPv4 rule applies.
     */
    public record IpAccess(
            @DefaultValue List<@NotBlank String> trustedProxies,
            @Min(1) @Max(32) @DefaultValue("8") int minTrustedPrefix,
            @NotNull @DefaultValue("ALLOW") Ipv6Policy ipv6Policy,
            @NotNull @DefaultValue("60s") Duration denyCacheTtl,
            @NotNull @DefaultValue("10m") Duration cleanupInterval,
            @Valid @NotNull @DefaultValue AutoDeny autoDeny) {
    }

    /**
     * {@code educore.ipaccess.ipv6-policy}: {@code ALLOW} (default) lets native IPv6 clients through (deny
     * rules are IPv4 only; rate limiting keys them by their /64 network); {@code DENY} answers every native
     * IPv6 client 403 {@code ipaccess/ipv6-unsupported}. IPv4-mapped IPv6 addresses are always treated as IPv4.
     */
    public enum Ipv6Policy {
        ALLOW,
        DENY
    }

    /**
     * {@code educore.ipaccess.auto-deny.*}: {@code failures} failed logins from one client IP within
     * {@code window} write a temporary deny rule ({@code source=AUTO}) for that IP that expires after
     * {@code duration}.
     */
    public record AutoDeny(
            @DefaultValue("true") boolean enabled,
            @Positive @DefaultValue("20") int failures,
            @NotNull @DefaultValue("10m") Duration window,
            @NotNull @DefaultValue("1h") Duration duration) {
    }

    /**
     * {@code educore.ratelimit.*}: request rate limits per minute (token buckets refilled every minute).
     * {@code anonymousPerMinute} per client IP for requests without a valid access token,
     * {@code authenticatedPerMinute} per account for requests with one, {@code publicPerMinute} per client IP
     * on {@code /api/v1/public/**}. {@code maxTrackedKeys} bounds the in-memory bucket store; once it is full,
     * callers without a bucket of their own share one overflow bucket of {@code overflowPerMinute} requests
     * (buckets are never evicted to make room, so an exhausted bucket cannot be reset by flooding new keys).
     * The login endpoint keeps its own limit ({@code educore.security.login.ip-attempts-per-minute}).
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @Positive @DefaultValue("60") int anonymousPerMinute,
            @Positive @DefaultValue("300") int authenticatedPerMinute,
            @Positive @DefaultValue("120") int publicPerMinute,
            @Positive @DefaultValue("100000") long maxTrackedKeys,
            @Positive @DefaultValue("1000") int overflowPerMinute) {
    }

    /**
     * {@code educore.ingestion.*}: CSV ingestion. {@code baseDir} holds {@code inbox/} (watched),
     * {@code processing/}, {@code done/} and {@code failed/}. A file must have been unchanged for
     * {@code stableAfter} before it is picked up; the inbox is scanned every {@code pollInterval}.
     * {@code threads} is the size of the multi-threaded batch step's executor; {@code skipLimit} the number of
     * rows a job may skip before it fails; {@code chunkSize} the rows written per transaction. Chunks running at
     * the same time count skips separately until they commit, so a job can skip up to
     * {@code skipLimit + threads * chunkSize} rows before the limit stops it. {@code maxRecordLength} caps one
     * physical line (characters). {@code lease} is how long an import stays owned by its instance without a
     * heartbeat; open imports whose lease expired are recovered (FAILED, {@code INTERRUPTED}).
     * <p>
     * Erasure of processed files (AC-08, docs/ops/DATA_RETENTION.md): the CSV of a SUCCEEDED or PARTIAL import
     * is deleted right after the import when {@code retainProcessedDays} is 0 (default; the database keeps only
     * the hash, metadata and counts in {@code imported_file} and the masked rows in {@code job_log_entry});
     * otherwise it is kept in {@code done/} (PARTIAL with its report) for that many days. FAILED and rejected
     * files and their reports stay in {@code failed/} for at most {@code retainFailedDays} (7). The cleanup runs
     * on {@code retentionCron} (UTC, hourly; {@code -} disables it).
     */
    public record Ingestion(
            @NotNull @DefaultValue("csv_uploads") Path baseDir,
            @NotNull @DefaultValue("20MB") DataSize maxBytes,
            @Positive @DefaultValue("50000") int maxRows,
            @DefaultValue("true") boolean pollerEnabled,
            @NotNull @DefaultValue("5s") Duration pollInterval,
            @NotNull @DefaultValue("2s") Duration stableAfter,
            @Positive @DefaultValue("4") int threads,
            @Positive @DefaultValue("10") int chunkSize,
            @PositiveOrZero @DefaultValue("1000") int skipLimit,
            @Positive @DefaultValue("10000") int maxRecordLength,
            @NotNull @DefaultValue("2m") Duration lease,
            @PositiveOrZero @Max(3650) @DefaultValue("0") int retainProcessedDays,
            @PositiveOrZero @Max(3650) @DefaultValue("7") int retainFailedDays,
            @NotBlank @DefaultValue("0 20 * * * *") String retentionCron) {
    }

    /**
     * {@code educore.webhook.*}: outbound webhook delivery. A failed delivery is retried at most
     * {@code maxRetries} times (so attempted at most {@code maxRetries + 1} times); retry {@code n} waits
     * {@code initialBackoff * 2^(n-1)} (capped at {@code maxBackoff}) plus up to 20 % random jitter. The
     * dispatcher looks for due deliveries every {@code dispatchInterval} and sends at most {@code batchSize}
     * per run. Each request (connect, TLS, status line and headers) must finish within
     * {@code requestDeadline}; the response body is never read. At most {@code maxSubscriptions} subscriptions
     * exist; a subscription holds at most {@code maxPendingPerSubscription} PENDING deliveries (further events
     * are dropped and counted); an ADMIN may request {@code testEventsPerMinute} test events per minute;
     * DELIVERED and FAILED deliveries are deleted after {@code deliveryRetention}.
     */
    public record Webhook(
            @NotNull @DefaultValue("5s") Duration connectTimeout,
            @NotNull @DefaultValue("5s") Duration readTimeout,
            @PositiveOrZero @DefaultValue("5") int maxRetries,
            @NotNull @DefaultValue("30s") Duration initialBackoff,
            @NotNull @DefaultValue("1h") Duration maxBackoff,
            @DefaultValue("true") boolean dispatcherEnabled,
            @NotNull @DefaultValue("5s") Duration dispatchInterval,
            @Positive @DefaultValue("20") int batchSize,
            @NotNull @DefaultValue("10s") Duration requestDeadline,
            @Positive @DefaultValue("20") int maxSubscriptions,
            @Positive @DefaultValue("1000") int maxPendingPerSubscription,
            @Positive @DefaultValue("5") int testEventsPerMinute,
            @NotNull @DefaultValue("14d") Duration deliveryRetention) {
    }

    /**
     * {@code educore.crypto.*}: {@code encryptionKey} ({@code EDUCORE_ENCRYPTION_KEY}) is the base64 of a
     * 32-byte AES-256 key that encrypts secrets at rest (webhook signing secrets, AES-256-GCM). Required in
     * {@code prod} (see {@link ProdStartupGuard}); outside {@code prod} a random per-process key is used when
     * unset, so stored secrets cannot be decrypted after a restart.
     */
    public record Crypto(String encryptionKey) {

        @Override
        public String toString() {
            return "Crypto[encryptionKey=" + redact(encryptionKey) + "]";
        }
    }

    /**
     * {@code educore.seo.*}: public site origin and AI crawler policy. {@code base-url} must be an absolute
     * {@code http}/{@code https} origin (host, optional port, optional trailing {@code /}) without user info,
     * path, query or fragment: it prefixes every sitemap and site-facts URL.
     */
    public record Seo(
            @NotNull @DefaultValue("http://localhost:3000") URI baseUrl,
            @Valid @NotNull @DefaultValue AiCrawlers aiCrawlers) {

        @AssertTrue(message = "educore.seo.base-url (EDUCORE_SEO_BASE_URL) must be an absolute http(s) origin "
                + "such as https://educore.example without user info, path, query or fragment")
        public boolean isBaseUrlAnOrigin() {
            if (baseUrl == null) {
                return true;
            }
            String scheme = baseUrl.getScheme();
            String path = baseUrl.getRawPath();
            return baseUrl.isAbsolute() && !baseUrl.isOpaque()
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && baseUrl.getHost() != null && !baseUrl.getHost().isEmpty()
                    && baseUrl.getRawUserInfo() == null
                    && baseUrl.getRawQuery() == null && baseUrl.getRawFragment() == null
                    && (path == null || path.isEmpty() || "/".equals(path));
        }
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

    /**
     * {@code educore.database.*}: the owner role Flyway migrates with ({@code EDUCORE_DB_MIGRATION_USERNAME} /
     * {@code EDUCORE_DB_MIGRATION_PASSWORD}). When set, the schema is migrated as that role while the application
     * connects with the least-privilege runtime role of {@code spring.datasource.*} (DML only;
     * {@code infra/postgres/app-role.sql}). Unset: Flyway uses the data source (local runs, tests). Required in
     * {@code prod} and distinct from the runtime role ({@link ProdStartupGuard}).
     */
    public record Database(String migrationUsername, String migrationPassword) {

        public boolean hasMigrationRole() {
            return migrationUsername != null && !migrationUsername.isBlank();
        }

        @AssertTrue(message = "EDUCORE_DB_MIGRATION_USERNAME and EDUCORE_DB_MIGRATION_PASSWORD must be set together "
                + "or not at all")
        public boolean isCompletePair() {
            return hasMigrationRole() == (migrationPassword != null && !migrationPassword.isBlank());
        }

        @Override
        public String toString() {
            return "Database[migrationUsername=" + migrationUsername + ", migrationPassword="
                    + (migrationPassword == null || migrationPassword.isBlank() ? "<unset>" : "<redacted>") + "]";
        }
    }
}
