package com.educore.ipaccess;

import com.educore.config.EduCoreProperties;
import com.educore.security.TrustedProxies;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventRecorded;
import com.educore.security.audit.SecurityEventType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;

/**
 * Automatic deny rules: when {@code educore.ipaccess.auto-deny.failures} (default 20) failed logins
 * ({@code AUTH_LOGIN_FAILURE} events, wrong credentials and attempts on a locked account alike) from one client
 * key fall within {@code window} (default 10 minutes), the client is denied the login endpoint for
 * {@code duration} (default 1 hour) and an {@code IP_RULE_CHANGED} event ({@code action=AUTO_CREATED}, or
 * {@code AUTO_EXTENDED} when the address already had one) is recorded.
 * <p>
 * Failures are counted per canonical client key ({@link ClientAddress#clientKey(String)}, the key of the login
 * limiter and the general rate limiter): an IPv4 address (IPv4-mapped forms included) or a native IPv6 /64, so
 * rotating addresses inside one /64 does not reset the count (R-04).
 * <ul>
 *   <li>IPv4: a STATIC deny rule with {@code source=AUTO} expiring after {@code duration} is written (one AUTO row
 *       per address, unique partial index {@code V13}, upsert).</li>
 *   <li>IPv6: deny rules are IPv4-only, so the /64 is denied in memory on this instance
 *       ({@link #isLoginDenied(ClientAddress)}, consulted by {@link IpAccessControlFilter}) until {@code duration}
 *       has passed; the event records the network ({@code kind=IPV6_NETWORK}, no rule id). It is not persisted
 *       and not shared between instances, exactly like the failure counter.</li>
 * </ul>
 * AUTO denials are login-scoped: {@link IpAccessControlFilter} refuses only {@code POST /api/v1/auth/login} for
 * them, so other users behind the same NAT address keep their sessions, refresh and the admin API.
 * <p>
 * Failures are counted in memory per instance (bounded Caffeine cache, entries expire after one idle window),
 * so the request path never touches the database for counting; only an IPv4 rule is persisted, in its own
 * transaction after the login transaction committed. Trusted proxies are never denied.
 */
@Component
public class IpAutoDenyService {

    private static final Logger log = LoggerFactory.getLogger(IpAutoDenyService.class);
    private static final long MAX_TRACKED_IPS = 100_000;
    /**
     * One AUTO rule per address (unique partial index of V13): a concurrent or repeated trigger extends the
     * existing rule. {@code xmax = 0} is true only for a freshly inserted row.
     */
    private static final String UPSERT_AUTO_RULE = "INSERT INTO ip_deny_rule "
            + "(kind, value, start_ip, end_ip, reason, source, expires_at, created_at) "
            + "VALUES ('STATIC', ?, ?, ?, ?, 'AUTO', ?, ?) "
            + "ON CONFLICT (start_ip) WHERE source = 'AUTO' DO UPDATE SET "
            + "expires_at = GREATEST(ip_deny_rule.expires_at, EXCLUDED.expires_at), reason = EXCLUDED.reason "
            + "RETURNING id, (xmax = 0) AS inserted";

    private final EduCoreProperties.AutoDeny settings;
    private final JdbcTemplate jdbc;
    private final IpDenyRuleCache cache;
    private final TrustedProxies trustedProxies;
    private final AuditService auditService;
    private final Clock clock;
    private final TransactionTemplate newTransaction;
    private final Cache<String, Deque<Instant>> failures;
    /** Denied IPv6 /64 networks (client key) and the end of their denial. */
    private final Cache<String, Instant> ipv6Denials;

    public IpAutoDenyService(EduCoreProperties properties, JdbcTemplate jdbc, IpDenyRuleCache cache,
                             TrustedProxies trustedProxies, AuditService auditService, Clock clock,
                             PlatformTransactionManager transactionManager) {
        this.settings = properties.ipaccess().autoDeny();
        this.jdbc = jdbc;
        this.cache = cache;
        this.trustedProxies = trustedProxies;
        this.auditService = auditService;
        this.clock = clock;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.failures = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_IPS)
                .expireAfterAccess(settings.window())
                .build();
        this.ipv6Denials = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_IPS)
                .expireAfterWrite(settings.duration())
                .build();
    }

    /** Runs after the transaction that recorded the event committed (immediately when there was none). */
    @TransactionalEventListener(fallbackExecution = true)
    public void onSecurityEvent(SecurityEventRecorded event) {
        if (settings.enabled() && event.type() == SecurityEventType.AUTH_LOGIN_FAILURE && event.ip() != null) {
            recordFailure(event.ip());
        }
    }

    /** Counts one failed login from {@code ip} under its client key; denies the client when the threshold is reached. */
    void recordFailure(String ip) {
        ClientAddress client = ClientAddress.parse(ip);
        if (!client.isValid() || trustedProxies.contains(client)) {
            return;
        }
        String key = client.rateLimitKey();
        Instant now = clock.instant();
        Deque<Instant> recent = failures.get(key, ignored -> new ArrayDeque<>());
        boolean reached;
        synchronized (recent) {
            Instant windowStart = now.minus(settings.window());
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(windowStart)) {
                recent.pollFirst();
            }
            recent.addLast(now);
            reached = recent.size() >= settings.failures();
            if (reached) {
                recent.clear();
            }
        }
        if (!reached) {
            return;
        }
        Optional<Ipv4> address = client.ipv4();
        if (address.isPresent()) {
            if (!cache.isDenied(address.get())) {
                deny(address.get(), now);
            }
        } else {
            denyIpv6Network(key, now);
        }
    }

    /**
     * Whether {@code client} is a native IPv6 address whose /64 is currently denied the login endpoint on this
     * instance. IPv4 clients are covered by the persisted rules ({@link IpDenyRuleCache}).
     */
    public boolean isLoginDenied(ClientAddress client) {
        if (!client.isIpv6()) {
            return false;
        }
        Instant until = ipv6Denials.getIfPresent(client.rateLimitKey());
        return until != null && clock.instant().isBefore(until);
    }

    /** Denies the IPv6 /64 {@code network} (its client key) the login endpoint for {@code duration}. */
    void denyIpv6Network(String network, Instant now) {
        Instant expiresAt = now.plus(settings.duration());
        Instant previous = ipv6Denials.asMap().put(network, expiresAt);
        boolean extended = previous != null && now.isBefore(previous);
        newTransaction.executeWithoutResult(status -> auditService.record(SecurityEventType.IP_RULE_CHANGED, null,
                null, network, Map.of("action", extended ? "AUTO_EXTENDED" : "AUTO_CREATED",
                        "kind", "IPV6_NETWORK", "source", IpDenyRuleSource.AUTO.name())));
        log.warn("IP_AUTO_DENIED network={} expiresAt={} scope=login persisted=false", network, expiresAt);
    }

    /** Writes (or extends) the AUTO rule for {@code address} in its own transaction; returns the rule id. */
    long deny(Ipv4 address, Instant now) {
        Long id = newTransaction.execute(status -> {
            Instant expiresAt = now.plus(settings.duration());
            Map<String, Object> row = jdbc.queryForMap(UPSERT_AUTO_RULE, address.toString(), address.toLong(),
                    address.toLong(), "Automatic: " + settings.failures() + " failed logins within "
                            + describe(settings.window()), Timestamp.from(expiresAt), Timestamp.from(now));
            long ruleId = ((Number) row.get("id")).longValue();
            boolean inserted = Boolean.TRUE.equals(row.get("inserted"));
            auditService.record(SecurityEventType.IP_RULE_CHANGED, null, null, address.toString(),
                    Map.of("action", inserted ? "AUTO_CREATED" : "AUTO_EXTENDED", "ipRuleId", ruleId,
                            "kind", IpRangeKind.STATIC.name(), "source", IpDenyRuleSource.AUTO.name()));
            cache.invalidateAfterCommit();
            log.warn("IP_AUTO_DENIED ip={} ruleId={} expiresAt={} scope=login", address, ruleId, expiresAt);
            return ruleId;
        });
        return id == null ? 0 : id;
    }

    private static String describe(Duration window) {
        long minutes = window.toMinutes();
        return minutes > 0 && window.equals(Duration.ofMinutes(minutes))
                ? minutes + " minute" + (minutes == 1 ? "" : "s")
                : window.toSeconds() + " seconds";
    }
}
