# Public site performance

Budget (`EDUCORE_UPGRADE_PROMPT.md` 7.6): LCP ≤ 2.5 s, CLS ≤ 0.1, JS ≤ 60 KB gzip and total ≤ 300 KB per public
page; Lighthouse performance ≥ 90, SEO ≥ 95, accessibility ≥ 95, best practices ≥ 95.

## How the budget is met

- **No hydration, islands only.** The public pages are complete static HTML. `scripts/postbuild-public.mjs`
  removes React Router's scripts and `modulepreload` links (the React runtime and the `/app` bundle) from every
  prerendered page and keeps only the public stylesheet. The single island is the catalog search
  (`app/public-site/islands/catalog-search.ts`, compiled on its own, about 0.6 KB gzip) on `/courses` and
  `/en/courses`. Navigation, the language switcher and sign-in are plain links. For comparison, the `/app` SPA
  shell preloads 19 scripts, 134.5 KB gzip.
- **Fonts.** IBM Plex Sans from the Fontsource files, self-hosted, latin + latin-ext only (`unicode-range`, so
  Turkish letters load the latin-ext file on demand), weights 400/500/600, `font-display: swap`; latin 400 and
  600 are preloaded.
- **CSS.** One stylesheet (`assets/public-<hash>.css`, 4.7 KB gzip incl. tokens), preloaded; no inline styles
  (CSP `style-src 'self'`).
- **Images.** The page body has no raster images; the logo is an SVG with explicit `width`/`height` and a
  `<picture>` dark variant. The OG images (1200×630 PNG, 27–40 KB) are only fetched by link previews.
- **No layout shift.** The search form is hidden without JavaScript inside a slot of fixed minimum height, so
  revealing it moves nothing; measured CLS is 0.

## Measured (build against the mock API, 8 courses, `npm run build:mock`)

`scripts/check-seo.mjs`, every one of the 30 pages: JS 0.0 KB (0.6 KB on the two catalog pages), initial transfer
(HTML + CSS + preloaded fonts + JS, gzip) 51.3–54.1 KB.

Lighthouse CI 0.15.1 (`npm run lighthouse`, mobile preset, simulated throttling, 3 runs, median, Chrome on
Windows, `scripts/serve-dist.mjs` with gzip and the production CSP):

| URL | Performance | Accessibility | Best practices | SEO | LCP | CLS | TBT |
|---|---|---|---|---|---|---|---|
| `/` | 99 | 100 | 100 | 100 | 1.7 s | 0 | 0 ms |
| `/courses` | 99 | 100 | 100 | 100 | 1.7 s | 0 | 0 ms |
| `/courses/dogrusal-cebir` | 99 | 100 | 100 | 100 | 1.7 s | 0 | 0 ms |

Reports are written to `frontend/dist/lighthouse/`; LHCI also creates `frontend/.lighthouseci/` (temporary,
should be git-ignored).
