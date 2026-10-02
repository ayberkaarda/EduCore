# Production Edge: TLS, Redirects and Security Headers

The production stack puts one nginx in front of everything. It terminates TLS, redirects HTTP to HTTPS,
serves the static React Router build, proxies `/api/` and the sitemaps to the backend, and sets the security
headers listed in `docs/security/HEADERS.md`. It is the only container with published ports.

| File | Purpose |
|---|---|
| `docker-compose.prod.yml` | Override: prod profile, edge ports 80/443, TLS mounts, optional `certbot` profile. |
| `infra/nginx/nginx.prod.conf` | Edge configuration (mounted as `/etc/nginx/conf.d/default.conf`). |
| `infra/nginx/snippets/tls-params.conf` | TLS 1.2/1.3, Mozilla intermediate ciphers, session cache, tickets off, OCSP stapling. |
| `infra/nginx/snippets/spa-security-headers.conf` | HSTS, SPA CSP, nosniff, `X-Frame-Options`, Referrer-, Permissions- and COOP policies. |
| `infra/nginx/snippets/api-security-headers.conf` | API header values (stricter CSP); upstream copies are hidden, so nothing is duplicated. |
| `infra/nginx/snippets/proxy-to-backend.conf` | Upstream `educore-backend:8080`, overwritten `X-Forwarded-*`, timeouts, buffering. |
| `infra/nginx/edge-start.sh` | Waits for the certificate, runs `nginx -t`, reloads every 6 h, starts nginx. |
| `infra/nginx/certbot/certbot-entrypoint.sh` | Certbot sidecar: placeholder certificate, HTTP-01 webroot issuance, renewal, install. |

The edge uses the frontend image itself (static build + unprivileged nginx, uid 101); the production
configuration is mounted. Container ports 8080/8443 are published as 80/443 (`EDUCORE_HTTP_PORT`,
`EDUCORE_HTTPS_PORT`).

## Starting the stack

```bash
cp .env.example .env    # fill every secret; SPRING_PROFILES_ACTIVE is forced to prod by the override
docker compose -f docker-compose.yml -f infra/backup/docker-compose.backup.yml -f docker-compose.prod.yml up -d
```

Setting `COMPOSE_FILE=docker-compose.yml:infra/backup/docker-compose.backup.yml:docker-compose.prod.yml` in
`.env` makes plain `docker compose ...` commands use the same three files. The override requires
(`:?required`): `EDUCORE_DB_NAME`, `EDUCORE_DB_USERNAME`, `EDUCORE_DB_PASSWORD`, `EDUCORE_JWT_SECRET`,
`EDUCORE_LOGIN_PEPPER`, `EDUCORE_ENCRYPTION_KEY`, `EDUCORE_SEO_BASE_URL`, `EDUCORE_BOOTSTRAP_ADMIN_USERNAME`
and `EDUCORE_BOOTSTRAP_ADMIN_PASSWORD`. It fixes `SPRING_PROFILES_ACTIVE=prod` and
`EDUCORE_IPACCESS_TRUSTED_PROXIES=172.30.42.10` (the edge's address on `educore-network`). It defaults
`EDUCORE_CORS_ALLOWED_ORIGINS` to empty, because the SPA and the API share one origin; the backend allows the
origin of `EDUCORE_SEO_BASE_URL` for the cookie-based refresh and logout calls.

Published ports: only the edge (80, 443). The HTTP redirect targets `https://$host$request_uri`, i.e. port 443; with a non-default `EDUCORE_HTTPS_PORT` (test setups) clients must use the HTTPS port directly. PostgreSQL, the backend API port 8080 and the management port 9090
stay inside `educore-network`.

## Building the edge image (prerendered public pages)

The edge image contains the public pages prerendered from the catalog of the deployment it serves
(docs/seo/BUILD.md). `docker-compose.prod.yml` therefore builds it with `BUILD_SCRIPT=build`:
- `PUBLIC_API_URL` is `EDUCORE_PUBLIC_API_URL`, or `EDUCORE_SEO_BASE_URL` (the running site) when that is empty.
- `PUBLIC_SITE_URL` is `EDUCORE_SEO_BASE_URL`.
- The build uses the host network.

The build fails when the API is unreachable, when it reports another base URL, or when no course is
published. An empty or mock catalog therefore never ships silently. Rebuild the edge after catalog changes:
`docker compose ... build educore-frontend && docker compose ... up -d educore-frontend`
(docs/seo/REBUILD_ON_CHANGE.md).

### First installation

On a new host no site is running yet, so the first edge image cannot be built from the real catalog:

1. Keep the host closed to the public: firewall ports 80/443 to your own address, or set `EDUCORE_HTTPS_PORT`
   to an unadvertised port.
2. Build a bootstrap edge from the development arguments (mock catalog) and start the stack without
   rebuilding it:
   `docker compose -f docker-compose.yml build educore-frontend`, then
   `docker compose -f docker-compose.yml -f infra/backup/docker-compose.backup.yml -f docker-compose.prod.yml build educore-backend backup`
   and `docker compose -f docker-compose.yml -f infra/backup/docker-compose.backup.yml -f docker-compose.prod.yml up -d --no-build`.
3. Sign in with the bootstrap ADMIN, change its password, and create and publish the real courses.
4. Rebuild the edge from the real catalog (`docker compose ... build educore-frontend`), restart it, check the
   public pages, then open the firewall.

## Certificates: two options

Both options deliver `fullchain.pem` and `privkey.pem` to `/etc/nginx/tls` in the edge container. The edge
waits up to 300 s (`EDGE_CERT_WAIT_SECONDS`) for them, then validates the configuration and starts. It
reloads every 6 h (`EDGE_RELOAD_INTERVAL`) so that renewed files take effect without a restart.

### Option A: certificates from a host directory

Use this for certificates issued elsewhere (corporate CA, DNS-01 automation, a load balancer's export).

1. Put `fullchain.pem` (certificate + intermediates) and `privkey.pem` in a host directory, readable by uid 101,
   e.g. `sudo install -d -m 0750 -g 101 /etc/educore/tls` and `chmod 0640` with group 101 on the key.
2. Set `EDUCORE_TLS_SOURCE=/etc/educore/tls` in `.env`.
3. Start the stack without a profile. After replacing the files, run `docker compose ... exec educore-frontend nginx -s reload`
   or wait for the periodic reload.

### Option B: Let's Encrypt via the certbot sidecar (`--profile certbot`)

1. Point the DNS records of every name in `EDUCORE_TLS_DOMAINS` to the host; port 80 must be reachable.
2. Set `EDUCORE_TLS_DOMAINS`, `EDUCORE_ACME_EMAIL`, and leave `EDUCORE_TLS_SOURCE=educore_tls`.
   For a dry run, set `EDUCORE_ACME_STAGING=true` (untrusted certificates, generous rate limits).
3. `docker compose ... --profile certbot up -d`.

Flow:
- The sidecar writes a two-day self-signed placeholder into the `educore_tls` volume, so nginx can start and
  answer the challenge.
- It runs `certbot certonly --webroot -w /var/www/acme`. nginx serves `/.well-known/acme-challenge/` on port 80
  from the shared `acme_webroot` volume; this is the only path, besides `/healthz`, that is not redirected.
- It copies the issued pair into `educore_tls` (key 0600, owner uid 101).
- Renewal: `certbot renew` every 12 h (`CERTBOT_RENEW_INTERVAL`). Failed issuance is retried every 10 minutes.
- nginx picks up new files on its next reload.

Let's Encrypt no longer embeds OCSP URLs in its certificates. nginx then logs that stapling is ignored, and
serves the certificate without a staple. Stapling stays configured for certificates that carry a responder URL.

## Behaviour

| Request | Response |
|---|---|
| `http://…/.well-known/acme-challenge/*` | File from the webroot (200/404), never redirected |
| `http://…/healthz` | `200 ok` (container healthcheck) |
| any other `http://…` | `301` to `https://$host$request_uri`, with nosniff, `X-Frame-Options`, `Referrer-Policy` (no HSTS over HTTP: RFC 6797 §7.2) |
| `https://…/api/**` | Proxied to `educore-backend:8080`; API header set incl. HSTS and `X-Robots-Tag: noindex, nofollow`, also on nginx errors (413, 502) |
| `/sitemap.xml`, `/sitemap-*.xml` | Proxied to the backend; on 404/5xx the file from the static build, if present |
| `/robots.txt`, `/llms.txt`, `/llms-full.txt` | Static build files, `Cache-Control: public, max-age=3600` |
| `/assets/**` | `Cache-Control: public, max-age=31536000, immutable` |
| `/`, `*.html` | `Cache-Control: no-cache` |
| `/app`, `/app/**` | SPA shell for non-files, `no-cache`, `X-Robots-Tag: noindex, nofollow` (the pages also carry a robots meta tag) |
| unknown path, `/nginx_status` | 404 with the SPA shell (no status endpoint exists) |

Every static response, including 404 and 413, carries HSTS (`max-age=63072000; includeSubDomains; preload`),
the SPA CSP (no `'unsafe-inline'`), `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`,
`Permissions-Policy` and `Cross-Origin-Opener-Policy`. The headers are set with `add_header … always`.
`server_tokens off` hides the nginx version.

Limits:
- Request bodies above 6 MB get a 413 (`client_max_body_size 6m`, the backend's `max-request-size`).
- Header and body timeouts are 15 s and 30 s.
- Proxy timeouts: connect 5 s, read and send 60 s.
- Request bodies are buffered before they reach the backend, and a failed request is never retried on another attempt.
- gzip is on for text types. The image has no brotli module.

Forwarded headers: nginx overwrites `X-Forwarded-For` with the connecting address, and `X-Forwarded-Proto`/
`X-Forwarded-Host`/`Host` with its own view. It clears `Forwarded` and sets `X-Forwarded-Port` to the public port (from the `Host` header, else 443), never the container port 8443, so the backend's same-origin check for CORS and for the refresh `Origin` sees the real public origin. A client
cannot inject an address or a scheme. The backend accepts these headers only from `172.30.42.10`. Rate limits,
login throttling, IP deny rules and audit records then see the real client address. Before this change,
HEADERS.md suggested `$proxy_add_x_forwarded_for`; overwriting is stricter, because the edge is the first hop.

## Verification

- `docker compose -f docker-compose.yml -f docker-compose.prod.yml config -q` with a complete `.env`.
- `nginx -t` with the mounted configuration, as `edge-start.sh` does on every start.
- After deployment:
  - `curl -sI http://<host>/` shows `301`.
  - `curl -sI https://<host>/` shows HSTS and CSP.
  - `curl -sI https://<host>/app/` shows `X-Robots-Tag`.
  - The SSL Labs scan grades A or better.
