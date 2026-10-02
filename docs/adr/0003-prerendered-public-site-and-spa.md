# 0003. Build-time prerendering for public pages, client-side SPA for the application

- Status: Accepted
- Date: 2026-09-25
- Original decision: D-03

## Context

The public course catalog must be indexable by search engines and readable by crawlers that do not run
JavaScript, while the signed-in application is an interactive single-page app. Two options were considered:

- A: React Router 7 framework mode with build-time prerendering of the public routes; no Node.js runtime in
  production; catalog pages are rebuilt when courses change.
- B: a runtime server-side rendering Node.js server.

## Decision

Option A. `frontend/react-router.config.ts` sets `ssr: false` and a `prerender()` function that fetches the
published courses from the backend public API at build time and prerenders every public path (including one
page per course slug). Everything under `/app` is a client-rendered SPA served from the SPA fallback. A build
fails when the public API is unreachable or returns an empty catalog. The static output is served by nginx.

## Consequences

Positive:

- No Node.js process in production: the frontend container is a static nginx image, which keeps the attack
  surface and resource use small.
- Crawlers receive complete HTML for public pages.

Negative:

- Course changes appear on prerendered pages only after a rebuild and redeploy of the frontend.
- The frontend build depends on a reachable backend public API.

## References

- [`frontend/react-router.config.ts`](../../frontend/react-router.config.ts)
- [`frontend/app/routes.ts`](../../frontend/app/routes.ts)
- [`frontend/Dockerfile`](../../frontend/Dockerfile)
- [`infra/nginx/nginx.prod.conf`](../../infra/nginx/nginx.prod.conf)
- [ADR 0028](0028-public-course-catalog.md)
