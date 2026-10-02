# Building the public site

The public pages are prerendered by React Router (framework mode, `ssr: false`) from the backend public API at
build time (decision D-03 A). There is no Node runtime in production.

## Environment

| Variable | Required | Meaning |
|---|---|---|
| `PUBLIC_API_URL` | build | Origin of the backend that serves `/api/v1/public/**`, e.g. `http://localhost:8080` (no path). |
| `PUBLIC_SITE_URL` | build | Absolute origin of the public site. Must equal the backend's `EDUCORE_SEO_BASE_URL`; the build compares it with `/api/v1/public/site-facts` `baseUrl` and fails on a mismatch. |
| `EDUCORE_SEO_AI_CRAWLERS_ALLOW_PUBLIC` | no | `true` (default) or `false`; mirrors `educore.seo.ai-crawlers.allow-public`. |
| `EDUCORE_SEO_AI_CRAWLERS_USER_AGENTS` | no | Comma-separated list; default `GPTBot,ClaudeBot,Claude-SearchBot,PerplexityBot,Google-Extended,CCBot`. |
| `PUBLIC_COURSE_MODE` | no | `Onsite` (default), `Online` or `Blended`: `CourseInstance.courseMode` in JSON-LD (the API has no per-course mode). |

`react-router dev` defaults `PUBLIC_API_URL` to `http://localhost:8080` and `PUBLIC_SITE_URL` to
`http://localhost:3000`; an unreachable API there only drops the course pages, with a warning.

## Commands

| Command | What it does |
|---|---|
| `npm run build` | `react-router build` (prerender list from the API) → `postbuild-public.mjs` → `externalize-inline-scripts.mjs` → `generate-seo-files.mjs` → `check-seo.mjs`. |
| `npm run build:mock` | Starts `scripts/mock-public-api.mjs` on a free port, runs `npm run build` against it with `PUBLIC_SITE_URL=http://localhost:4173`, then `npm run test:prerender`. |
| `npm run mock:public-api` | The mock alone (`MOCK_PUBLIC_API_PORT`, default 8787; `MOCK_SITE_URL`). |
| `npm run serve:dist` | Serves `dist/client` on port 4173 like nginx (routing, gzip, CSP and other headers). |
| `npm run lighthouse` | Lighthouse CI (`lighthouserc.cjs`) against `serve:dist`; needs `CHROME_PATH`. |
| `npm run og-images` | Re-renders `public/og/*.png` and `public/brand/logo-512.png` with headless Chrome (`CHROME_PATH`). |

## Failure modes (the build never publishes an empty catalog)

| Situation | Result |
|---|---|
| `PUBLIC_API_URL` or `PUBLIC_SITE_URL` unset or not an origin | `public-site: BUILD FAILED. PUBLIC_API_URL is not set. ...`, exit 1 |
| API unreachable, non-200, non-JSON, malformed course | `public-site: BUILD FAILED. The public API is unreachable at <url> (...)`, exit 1 |
| No published course | `... returned no published courses. Publish at least one course ...`, exit 1 |
| `PUBLIC_SITE_URL` differs from the backend's base URL | `... differs from the backend's EDUCORE_SEO_BASE_URL ...`, exit 1 |
| A page misses a title, description, canonical, hreflang, H1 or valid JSON-LD; JS over budget | `check-seo: N problem(s)`, exit 1 |

CI must therefore start the backend (compose) and publish at least one course before `npm run build`.
The frontend `Dockerfile` runs `npm run build` and needs both variables as build arguments; that wiring is done in the
infrastructure configuration (`frontend/Dockerfile`, `docker-compose.prod.yml`).

## Output (dist/client)

- `index.html`, `courses/index.html`, `courses/<slug>/index.html`, `about/`, `faq/`, `privacy/`, `security/`
  and the same under `en/`; `404.html`, `en/404.html`; `__spa-fallback.html` (the `/app` shell).
- `robots.txt`, `sitemap-static.xml`, `llms.txt`, `llms-full.txt`, `og/*.png`, `brand/logo-512.png`.
- `assets/public-<hash>.css`, `assets/catalog-search-<hash>.js` (the only script on public pages).
