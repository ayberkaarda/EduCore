# Security Headers, CORS and HTTPS

Status: implemented in the API since phase P5 (`com.educore.security.SecurityHeaders`, `SecurityConfig`,
`config/ForwardedHeadersConfig`). The reverse proxy configuration (`infra/nginx/`, a later step) must mirror the
values in this document exactly; the API values are verified by `SecurityHeadersIT`, `CorsIT` and
`HttpsRedirectIT`.

## Request path (application port)

```
client -> nginx (TLS, static SPA, /api/ proxy)
       -> HTTPS requirement (prod: 403 request/https-required for plain HTTP)
       -> security headers
       -> IpAccessControlFilter (403 ipaccess/denied; also refuses preflights from denied addresses)
       -> CORS (403 request/cors-rejected; preflights end here)
       -> RateLimitFilter (429 rate-limit/exceeded)
       -> JwtAuthenticationFilter -> URL rules and @PreAuthorize -> controller
```

The management port (actuator, not published) has its own chain: same response headers, no HTTPS
requirement, no IP deny rules and no rate limiting.

## API response headers (set by Spring Security on every response of both ports)

| Header | Value |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Permissions-Policy` | `accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()` |
| `Cross-Origin-Opener-Policy` | `same-origin` |
| `Cache-Control` | `no-cache, no-store, max-age=0, must-revalidate` (Spring default; the public catalog sets `max-age=300, public` itself) |
| `X-XSS-Protection` | `0` (Spring default: the legacy filter is disabled, CSP is the control) |
| `Strict-Transport-Security` | `max-age=63072000 ; includeSubDomains ; preload` — only when `educore.security.https.required=true` (the `prod` profile) **and** the request is secure; never in `dev`/`test` |
| `X-Robots-Tag` | `noindex, nofollow` on every `/api/**` response (`publicapi.RobotsTagFilter`); not on `/sitemap.xml` |

Problem responses (401, 403, 404, 429, ...) carry the same headers. The 403 `request/https-required` answer
is written before Spring's header writer runs; it sets the same headers itself, except HSTS (it is plain HTTP,
where HSTS is meaningless). `HttpsRedirectIT` asserts this, and that the management port carries the policy
without HSTS.

## SPA response headers (to be set by nginx on the static site, not on `/api/`)

| Header | Value |
|---|---|
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'` |
| `Strict-Transport-Security` | `max-age=63072000; includeSubDomains; preload` (HTTPS server block only) |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Permissions-Policy` | `accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()` |
| `Cross-Origin-Opener-Policy` | `same-origin` |

Rules for the nginx step:

- Use `add_header ... always;` so error pages carry the headers too.
- Do not add these headers in the `/api/` location: the API sets its own (stricter CSP); duplicated headers
  are combined by browsers (two CSPs both apply, two `X-Frame-Options` values are invalid).
- No `'unsafe-inline'` for scripts, ever. If inline styles prove unavoidable, use a per-response nonce, not
  `'unsafe-inline'`.
- Plain HTTP (port 80) answers `301` to `https://$host$request_uri`; only the ACME challenge path may stay on
  HTTP.

## CORS (`SecurityConfig.corsConfigurationSource`, the only CORS configuration; no `@CrossOrigin`)

| Setting | Value |
|---|---|
| Allowed origins | `educore.cors.allowed-origins` (env `EDUCORE_CORS_ALLOWED_ORIGINS`, comma-separated); `http://localhost:3000` in `dev`/`test`; empty in `prod` (SPA and API share one origin behind nginx) |
| Allowed methods | `GET, POST, PUT, DELETE, OPTIONS` |
| Allowed request headers | `Authorization, Content-Type, Accept, Accept-Language, If-None-Match, X-Request-Id` |
| Exposed headers | `Retry-After, ETag, X-Request-Id` |
| `Access-Control-Allow-Credentials` | `true` only under `/api/v1/auth/**` (the refresh cookie's path: login sets it, refresh/logout send it); absent everywhere else |
| `Access-Control-Max-Age` | `600` |

A request from another origin, or a preflight asking for another method or header, is answered 403
`request/cors-rejected` without any `Access-Control-Allow-*` header. Cross-origin clients must therefore send
cookies (`credentials: 'include'` / `withCredentials`) only on the auth calls.

## HTTPS and forwarded headers

- TLS terminates at nginx. The API listens on plain HTTP inside the compose network.
- `server.forward-headers-strategy=native` installs Tomcat's `RemoteIpValve`. `ForwardedHeadersConfig` replaces
  its default trust list (all private, loopback and link-local peers) with exactly
  `educore.ipaccess.trusted-proxies` (env `EDUCORE_IPACCESS_TRUSTED_PROXIES`, bound explicitly in
  `application.yml`; IPv4 addresses or CIDR blocks, comma-separated; an invalid entry, or a block broader than
  `educore.ipaccess.min-trusted-prefix` = `/8` such as `0.0.0.0/0`, fails startup). `docker-compose.yml` sets it
  to the nginx container's fixed address. With the list empty (default) no peer is trusted and both
  headers are ignored.
- Only from a trusted peer: `X-Forwarded-For` sets the client address (right-most hop that is not itself a
  trusted proxy; several header lines are one list) and `X-Forwarded-Proto: https` marks the request secure.
  `ClientIpResolver` applies the same rule for IP access control, rate limiting, login throttling and audit
  records, after normalising IPv4-mapped IPv6 and `ip:port` forms (`docs/security/IP_ACCESS.md`). A malformed
  right-most hop is refused with 400 `ipaccess/invalid-client-address`. `ForwardedHeadersTomcatIT` covers this
  on a real Tomcat.
- `educore.security.https.required` (`true` in `application-prod.yml`): every application request must be
  secure (`requiresChannel().anyRequest().requiresSecure()`). A request that is not secure is answered **403
  `request/https-required`** (problem+json), **not redirected**: a redirect would make API clients replay the
  method, body and `Authorization` header, and browsers never reach the API over plain HTTP because nginx
  already answers `301`. Secure responses carry HSTS. The management port is excluded.
- The refresh cookie is `Secure` in every profile except `dev` (`educore.security.refresh-token.cookie-secure`).

nginx must therefore (later step):

```
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;     # overwrite, never pass a client-supplied value
proxy_set_header Host              $host;
```

and its container address (or the compose network's CIDR) must be listed in
`EDUCORE_IPACCESS_TRUSTED_PROXIES`; otherwise every request in `prod` is refused with
`request/https-required`.
