# OWASP ZAP Baseline Results

Scan of 2026-10-02 with `scripts/zap-local.sh` (ZAP `stable` image pinned by digest, rules in `zap/baseline.conf`,
3-minute spider plus passive rules).

**Target:** the production-shaped stack (`docker-compose.yml` + `docker-compose.prod.yml`):
- PostgreSQL;
- the backend in the `prod` profile;
- the TLS edge (`infra/nginx/nginx.prod.conf`) with a throwaway self-signed certificate, reached as
  `https://educore-frontend:8443` from inside the compose network.

The edge image was built with the mock public catalog (`BUILD_SCRIPT=build:mock`), as in CI. Header, cookie
and routing behaviour do not depend on the catalog content.

**Result:**
- 0 High and 0 FAIL-rule alerts. 60 rules passed and 2 informational rules are ignored by configuration.
- Alert types: 1 Medium, 3 Low, the rest informational. Each one is listed below with its disposition.
- The nightly `zap.yml` workflow repeats the scan; it fails on any High alert or FAIL rule.

| Rule | Risk | Where | Disposition |
|---|---|---|---|
| 10099 Source Code Disclosure (SQL pattern) | Medium | `/en` | **False positive.** The pattern matches English page copy ("… select their term courses from their profile …"). No SQL or server code reaches responses: problem details never carry exception text (`ProblemDetailsIT`). The rule is WARN instead of FAIL, with this reason in `zap/baseline.conf`. Every new instance is reviewed. |
| 90004 Cross-Origin-Embedder-Policy / Cross-Origin-Resource-Policy missing | Low | `/app/**`, `/assets/**` | **Accepted, improvement candidate.** COOP `same-origin` is set; COEP/CORP are not in the header set of `docs/security/HEADERS.md`. Adding `Cross-Origin-Resource-Policy: same-origin` is safe for this same-origin site; COEP `require-corp` needs a check of every embedded resource first. Kept as WARN. |
| 2 Private IP Disclosure | Low | `/assets/error-messages-*.js` | **False positive.** `192.168.1.5` is the example in the IP-allocation form's placeholder and validation message, not an address of the deployment. |
| 10027 Suspicious Comments | Informational | `/assets/jsx-runtime-*.js` | **Accepted.** The comment text comes from the React runtime bundle ("in response to some user interaction …"); it is not project code. |
| 10015 Re-examine Cache-control Directives | Informational | HTML pages, `/app/` | **Intended.** HTML is served with `Cache-Control: no-cache` so that deploys take effect at once; hashed assets are `immutable` (docs/ops/TLS.md). |
| 10049 Content Cacheability (3 variants) | Informational | assets, `/app`, `/api/` | **Ignored by configuration** (informational; the cache policy is deliberate). |
| 10109 Modern Web Application | Informational | `/app/` | **Ignored by configuration** (informational). |

Checks that passed and are configured as FAIL include:
- security headers: HSTS, CSP, `X-Frame-Options`, `X-Content-Type-Options`, Permissions-Policy;
- cookie flags: HttpOnly, Secure, SameSite;
- no version, `X-Powered-By`, debug or backend-server headers;
- no stack traces or debug error messages;
- no mixed content;
- CORS without a wildcard;
- no vulnerable JavaScript library (Retire.js) and no scripts from foreign or known-malicious domains.

Reproduce:

```bash
docker compose -f docker-compose.yml build educore-frontend
docker compose -f docker-compose.yml -f docker-compose.prod.yml build educore-backend
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-build --wait
ZAP_NETWORK=<project>_educore-network scripts/zap-local.sh https://educore-frontend:8443
```

The reports (HTML, JSON, Markdown) are written to `zap/reports/`, which git ignores.
