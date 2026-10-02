# IP Access Control and Rate Limiting

Status: implemented in phase P5 (decision D-09). Package `com.educore.ipaccess` (deny rules, allocation
ranges, `Ipv4`/`Ipv4Range` value objects) and `com.educore.ratelimit`. Tests: `IpAccessControlIT`,
`AutoDenyIT`, `RateLimitIT`, `TrustedProxiesTest`, `CaffeineRateLimitStoreTest`, `Ipv4Test`, `Ipv4RangeTest`.

## Two different IP lists (D-09)

| | IP allocation ranges | IP deny rules |
|---|---|---|
| Table | `ip_allocation_range` (was `ip_block`, renamed by `V12__ip_access.sql`) | `ip_deny_rule` (new in `V12__ip_access.sql`) |
| Entity | `IpAllocationRange` (was `IpBlock`) | `IpDenyRule` |
| Purpose | Student IP allow-list: an `ipAddress` assigned to an account must lie inside one range | Request-level blocking: requests from a covered client IP are refused |
| Admin API | `/api/v1/admin/ip-allocations` (was `/api/v1/admin/ip-rules` before P5) | `/api/v1/admin/ip-rules` (new meaning since P5) |
| Audit | `IP_ALLOCATION_CHANGED` `{action, ipAllocationId, type}` | `IP_RULE_CHANGED` `{action, ipRuleId, kind, source}` |

Rules are IPv4 only. Rule values are strict dotted quads (no leading zeros, no whitespace, no other
notations); CIDR blocks must use the network address (`10.0.0.0/8`, not `10.0.0.5/8`). IPv6 deny-rule input is a
400 `ip-rule/ipv6-unsupported` (BACKLOG B-050).

## Client address normalisation (`ClientAddress`)

Every peer address and `X-Forwarded-For` hop is normalised once, and the same result feeds the trusted-proxy
check, the deny check, the rate-limit key and the auto-deny counter:

| Input | Result |
|---|---|
| `198.18.6.6`, `198.18.6.6:4711` | IPv4 `198.18.6.6` (port dropped) |
| `::ffff:198.18.6.6`, `::ffff:c612:606`, `0:0:0:0:0:ffff:c612:606`, `[::ffff:198.18.6.6]:443` | IPv4 `198.18.6.6` (mapped) |
| `2001:db8::1`, `[2001:db8::1]:443` | IPv6 (canonical eight groups); rate-limit key = its /64 network |
| anything else (`localhost`, `fe80::1%eth0`, junk) | invalid |

Policy for native IPv6 clients (`educore.ipaccess.ipv6-policy`): `ALLOW` (default) — they pass, no IPv4 rule can
apply, they are rate limited per /64 (one subscriber usually holds a whole /64); `DENY` — 403
`ipaccess/ipv6-unsupported`. The compose deployment is IPv4-only, so `ALLOW` changes nothing there.

## Client IP resolution

`ClientIpResolver` (also used by login throttling, rate limiting and audit records): the socket peer, unless
the peer lies inside `educore.ipaccess.trusted-proxies`; then the right-most `X-Forwarded-For` hop that is not
itself a trusted proxy. Several header lines are one list in order (as Tomcat combines them). Hops left of the
chosen one were written by the client and are ignored. If the chosen hop is not an address (only a misbehaving
trusted proxy can produce this), the request is refused with 400 `ipaccess/invalid-client-address` — Tomcat's
`RemoteIpValve` (narrowed to the same list, see `HEADERS.md`) would set exactly that value as the peer, so both
paths agree. With no trusted proxy (default) the header is ignored entirely.

Trusted proxies: IPv4 addresses or CIDR blocks no broader than `educore.ipaccess.min-trusted-prefix` (default
`/8`); `0.0.0.0/0` or `1.0.0.0/7` fail startup. A deny rule can never cover a trusted proxy (409
`ip-rule/trusted-proxy`), so blocking one client never blocks everyone behind the proxy.

## Deny rules

Columns: `id`, `kind` (`STATIC` | `RANGE` | `CIDR`), `value` (canonical text), `start_ip`/`end_ip` (inclusive
unsigned 32-bit bounds, computed by the server), `reason` (≤ 200, optional), `source` (`MANUAL` | `AUTO`),
`expires_at` (`NULL` = permanent), `created_by` (ADMIN account id, `NULL` for `AUTO`), `created_at`.

`IpAccessControlFilter` runs first in the application chain (before CORS, so preflights from denied
addresses are refused too; then rate limiting and token authentication):

1. Resolve and normalise the client address (above). Invalid: 400 `ipaccess/invalid-client-address`. Native
   IPv6: pass, or 403 `ipaccess/ipv6-unsupported` under `ipv6-policy=DENY`.
2. Check the in-memory snapshot of active rules (`IpDenyRuleCache`): reloaded from the database at most every
   `educore.ipaccess.deny-cache-ttl` (60 s); every change through the admin API, the automatic rule and the
   cleanup invalidate it at once (again after commit). No database query per request. With several API
   instances, a change made on one instance reaches the others within one TTL.
3. A MANUAL rule covers the address: every request is answered 403 problem `ipaccess/denied` ("Requests from
   this network address are not accepted."), without the rule's value, reason or expiry. Only AUTO rules cover
   it: only `POST /api/v1/auth/login` is refused that way (see below). The log line `IP_DENIED ip=<masked>` is
   written at most once per address and minute (the logging pipeline masks the last octet). No audit event per
   request.

Load failures (fail-safe policy): when a reload fails, the previous snapshot stays in force and the reload is
retried after 5 s (stale rules are safer than none). When no snapshot was ever loaded (the database is
unreachable since startup), requests are refused with 503 `ipaccess/unavailable` — the deny list fails closed.
`IpDenyRuleCacheTest` covers both cases.

Rules whose `expires_at` has passed stop matching at that instant (the snapshot compares with the clock) and
are deleted by `IpDenyRuleCleanup` every `educore.ipaccess.cleanup-interval` (10 min).

Admin API guards (`IpDenyRuleService`): a rule may not cover the ADMIN's own current client IP (409
`ip-rule/self-deny`) or any trusted proxy (409 `ip-rule/trusted-proxy`); `expiresAt` must lie in the future.

## Automatic deny rule (failed logins)

`educore.ipaccess.auto-deny.*`: `failures` (20) failed logins from one client key within `window` (10 min) write
a STATIC rule for that IP with `source=AUTO` and `expires_at = now + duration` (1 h), plus an
`IP_RULE_CHANGED` event `{action: AUTO_CREATED, ...}` (`AUTO_EXTENDED` when the address already had an AUTO
rule).

Client key (since fix1, R-04): failures are counted under the shared canonical client key
(`ClientAddress.clientKey`), the same key as the login limiter and the general rate limiter: the IPv4 address
(mapped IPv6 and `ip:port` forms normalised to it) or, for native IPv6, the /64 network. Rotating the interface
identifier inside one /64 therefore neither resets the auto-deny count nor yields a fresh login bucket. Deny rules
are IPv4-only, so a /64 that reaches the threshold is denied the login endpoint **in memory on that instance** for
`duration` (`IpAutoDenyService.isLoginDenied`, checked by `IpAccessControlFilter` for `POST /api/v1/auth/login`
only); the event is `IP_RULE_CHANGED {action: AUTO_CREATED|AUTO_EXTENDED, kind: IPV6_NETWORK, source: AUTO}`
with the network in `ip` and no rule id. Like the failure counters it is per instance and lost on restart. A failed login is every `AUTH_LOGIN_FAILURE` security event (wrong credentials, inactive account, attempt
on a locked account, wrong current password on a password change).

AUTO rules are **login-scoped**: they refuse only `POST /api/v1/auth/login` (403 `ipaccess/denied`). Many users
can share one public address (school or office NAT); a password-guessing burst from one of them must not lock
the others out of their running sessions, `/auth/refresh`, `/auth/password` or the admin API. Refresh stays
open on purpose: it needs a valid refresh cookie, which a guessing client does not have. MANUAL rules block
every request.

One AUTO row per address: a unique partial index (`V13__ip_deny_auto_unique.sql`,
`ON ip_deny_rule (start_ip) WHERE source = 'AUTO'`) and an `INSERT ... ON CONFLICT DO UPDATE` that keeps the
later expiry, so concurrent triggers (threads or instances) extend one rule instead of adding duplicates.

Design: `AuditService` publishes `SecurityEventRecorded` for every event; `IpAutoDenyService` listens after the
login transaction has committed and counts failures in a bounded in-memory sliding window per IP (Caffeine,
at most 100,000 addresses, entries expire after one idle window). The request path therefore never queries
`login_attempt`; only the resulting rule is persisted, in its own transaction. Counters are per instance and
reset on restart (an attacker spread over several instances needs proportionally more attempts); the per-IP
login throttle (10 per minute, P2) keeps applying, so 20 failures take at least two minutes. Trusted proxies and
non-IPv4 addresses are never auto-denied.

## Rate limiting

`RateLimitFilter` runs after `IpAccessControlFilter` and CORS (preflights are not counted) and before
`JwtAuthenticationFilter`. One Bucket4j token
bucket per key, refilled in full every minute:

| Request | Key | Limit (`educore.ratelimit.*`) |
|---|---|---|
| `/api/v1/public/**` | client IP | `public-per-minute` = 120 |
| `Authorization: Bearer` token that verifies | account id (`sub`) | `authenticated-per-minute` = 300 |
| anything else (no token, or a token that does not verify) | client IP | `anonymous-per-minute` = 60 |
| `POST /api/v1/auth/login` | — (not counted here) | P2 limit: `educore.security.login.ip-attempts-per-minute` = 10 per IP |

`GET /api/v1/weather` is additionally protected by its own provider cache (P6). A rejected request gets 429
`rate-limit/exceeded` with `Retry-After` in whole seconds (≥ 1).

Why the token is verified in the rate limiter instead of placing a second stage after
`JwtAuthenticationFilter`: the per-account bucket must apply before the authentication filter loads the
account from the database, otherwise a flood of authenticated requests would still cost one query each. The
check is the same signature/issuer/audience/expiry verification as in `JwtService.parse` (HMAC, no I/O); a
forged or expired token never reaches the account bucket and is counted in the anonymous per-IP bucket. A
deleted or demoted account keeps its bucket until it expires, which only ever limits it further.

Store: `RateLimitStore` interface, `CaffeineRateLimitStore` implementation — admission instead of eviction:
at most `educore.ratelimit.max-tracked-keys` (100,000) callers get a bucket of their own, each dropped after two
idle minutes (by then it would be full again). When the store is full, an existing bucket is never evicted to
make room (that would hand an exhausted caller a fresh, full bucket); callers without a bucket share one
overflow bucket of `overflow-per-minute` (1,000) requests until idle buckets expire. A hard Caffeine size cap
slightly above the admission limit bounds memory. Limits are per instance; a shared store (Redis) is BACKLOG
B-051.

IP keys use the normalised client address (mapped IPv6 and ports removed; native IPv6 by /64). The login limiter
(`educore.security.login.ip-attempts-per-minute`, 10) uses the same key and its own store with the same admission
and overflow policy (`CaffeineBucketLoginRateLimiter`, at most 100,000 clients, newcomers to a full store share an
overflow bucket of `overflow-per-minute`); before fix1 it keyed IPv6 by the full address and evicted buckets.

The `test` profile raises the three limits and the auto-deny threshold (MockMvc requests share
`127.0.0.1`); `RateLimitIT` and `AutoDenyIT` run with the production values.
