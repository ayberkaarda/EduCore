# 0025. Perimeter: HTTPS requirement, trusted proxies, IP deny order and rate limiting

- Status: Accepted
- Date: 2026-10-02
- Original decisions: D-NEW-50, D-NEW-51

## Context

TLS terminates at nginx, so the backend sees plain HTTP and must decide which forwarded headers to trust.
Tomcat's `RemoteIpValve` trusts every private peer by default. Rate limiting and IP deny rules must use the same
client address, must not cost a database query per request, and must not lock signed-in users out of a shared
school NAT address after someone else's failed logins.

## Decision

- HTTPS: with `educore.security.https.required=true` (`prod`), a request that is not secure is answered 403
  `request/https-required` instead of a redirect, because a redirect would make API clients replay method, body
  and `Authorization` header; nginx already redirects browsers.
- Trusted proxies: `ForwardedHeadersConfig` narrows `RemoteIpValve` to `educore.ipaccess.trusted-proxies`
  (compose: the nginx address `172.30.42.10`). Entries broader than `/8` (`min-trusted-prefix`) fail startup.
- Address normalisation (`ClientAddress`): IPv4-mapped IPv6 and `ip:port` forms are normalised before every
  trust, deny and rate decision. Native IPv6 passes by default (`ipv6-policy=ALLOW`) and is rate-limited per /64.
- Filter order in the application chain: `IpAccessControlFilter` before CORS (so preflights from denied addresses
  are refused), `RateLimitFilter` after CORS (preflights are not counted) and before `JwtAuthenticationFilter`.
  The rate limiter keys authenticated requests by the verified token subject without a database lookup.
- AUTO deny rules (failed logins) refuse only `POST /api/v1/auth/login`; MANUAL rules refuse everything. Failed
  logins for auto-deny are counted in memory per instance.
- `CaffeineRateLimitStore` admits new keys only below its size limit; further callers share an overflow bucket
  (no eviction). `IpDenyRuleCache` keeps its last snapshot on reload failure and fails closed (503) without one.
- The P5 migrations need a one-time `SPRING_FLYWAY_OUT_OF_ORDER=true` start on older databases.

## Consequences

Positive:

- Forwarded headers are honoured from exactly one peer; spoofed `X-Forwarded-For` from clients is ignored.
- Flooding requests are rejected before any database access.

Negative:

- A missing or wrong trusted-proxy setting in `prod` makes every request fail with `request/https-required`.
- In-memory counters are per instance; several instances each apply their own limits.

## References

- [`src/main/java/com/educore/security/SecurityConfig.java`](../../src/main/java/com/educore/security/SecurityConfig.java)
- [`src/main/java/com/educore/config/ForwardedHeadersConfig.java`](../../src/main/java/com/educore/config/ForwardedHeadersConfig.java)
- [`src/main/java/com/educore/security/TrustedProxies.java`](../../src/main/java/com/educore/security/TrustedProxies.java)
- [`src/main/java/com/educore/ipaccess/ClientAddress.java`](../../src/main/java/com/educore/ipaccess/ClientAddress.java)
- [`src/main/java/com/educore/ratelimit/RateLimitFilter.java`](../../src/main/java/com/educore/ratelimit/RateLimitFilter.java)
- [`src/main/java/com/educore/ratelimit/CaffeineRateLimitStore.java`](../../src/main/java/com/educore/ratelimit/CaffeineRateLimitStore.java)
- [`src/main/java/com/educore/ipaccess/IpDenyRuleCache.java`](../../src/main/java/com/educore/ipaccess/IpDenyRuleCache.java)
- [`src/test/java/com/educore/security/HttpsRedirectIT.java`](../../src/test/java/com/educore/security/HttpsRedirectIT.java)
- [`src/test/java/com/educore/ratelimit/RateLimitIT.java`](../../src/test/java/com/educore/ratelimit/RateLimitIT.java)
- [`docs/security/HEADERS.md`](../security/HEADERS.md)
- [`docs/security/IP_ACCESS.md`](../security/IP_ACCESS.md)
- [`docs/ops/UPGRADE.md`](../ops/UPGRADE.md)
