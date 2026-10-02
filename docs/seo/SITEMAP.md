# Sitemaps and nginx fallback order

| Source | URL | Content | Authority |
|---|---|---|---|
| Backend `SitemapController` (runtime) | `/sitemap.xml`, `/sitemap-courses-<n>.xml` | Turkish public URLs; `lastmod` = course `updatedAt`, catalog revision for `/` and `/courses`; index file above `educore.seo.sitemap-max-urls` | Primary: always current |
| Build artefact (`generate-seo-files.mjs`) | `dist/client/sitemap-static.xml` | Both languages, `xhtml:link` hreflang alternates (`tr`, `en`, `x-default`); same `lastmod` rules | Fallback only |

The artefact is deliberately **not** named `sitemap.xml`: a static file of that name would shadow the backend
route on any server that tries files first (the dev `frontend/nginx.conf` does), and a stale build would then
win over the live catalog. `robots.txt` names only `<base-url>/sitemap.xml`.

## nginx order (`infra/nginx/nginx.prod.conf`)

1. `location = /sitemap.xml` and `location ~ ^/sitemap-[a-z0-9-]+\.xml$`: proxy to the backend with
   `proxy_intercept_errors on`.
2. On 404/5xx from the backend: `error_page ... = @static_sitemap`.
3. `@static_sitemap` must serve the artefact: **change** `try_files $uri =404;` to
   `try_files /sitemap-static.xml =404;` (today it looks for a static `/sitemap.xml`, which the build no longer
   writes). Requests for `/sitemap-courses-<n>.xml` then also fall back to the full static sitemap, which lists
   every URL, so nothing is lost.
4. `/sitemap-static.xml` itself matches the regex location and is proxied; the backend answers it with an error.
   It is an internal fallback target, not a public URL, so that is acceptable.

Other nginx changes the public site needs (both `frontend/nginx.conf` and `nginx.prod.conf`):

- Unknown public paths: `error_page 404 /404.html;` and, inside `location ^~ /en/`, `error_page 404 /en/404.html;`
  instead of the SPA shell, so crawlers get a real 404 with the static not-found page.
- `location = /en` must resolve `en/index.html` (the generic `try_files $uri $uri/index.html` already does).
- Course pages that were unpublished after the build answer 404 until the next rebuild
  (`docs/seo/REBUILD_ON_CHANGE.md`).

## lastmod

- Course page: `updatedAt` of the course (W3C date-time, UTC, seconds).
- `/` and `/courses`: `SiteFacts.dateModified` (catalog revision).
- `/about`, `/faq`, `/privacy`, `/security`: no `lastmod` (no stored modification date), as in the backend.
