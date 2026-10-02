# Meta tags of the public pages

Built by `frontend/app/seo/meta.ts` (`pageMeta`) from each route's `meta()`; checked on every build by
`scripts/check-seo.mjs`.

| Element | Rule |
|---|---|
| `<title>` | `<Page> · EduCore`, at most 60 characters (`app/seo/text.ts` `pageTitle` shortens long course names at a word boundary). Landing: `EduCore — Course and Student Management Platform` / `EduCore — Ders ve Öğrenci Yönetim Platformu`. Unique within a language. |
| `meta description` | 140–160 characters, unique. Hand-written for content pages (tested in `tests/public-copy.test.ts`); generated for course pages from name, term, instructor and description (`course-text.ts`). |
| `link rel=canonical` | Absolute, from `PUBLIC_SITE_URL` (= `EDUCORE_SEO_BASE_URL`), the page's own language version, no trailing slash except `/`. |
| `link rel=alternate hreflang` | `tr`, `en`, `x-default` (Turkish) on every indexable page. |
| `meta robots` | `index, follow` on public pages; `noindex, follow` and no canonical on the 404 pages; `noindex, nofollow` on the `/app` shell and every `/app/**` render (`app/root.tsx`). nginx adds `X-Robots-Tag` for `/app` and `/api`. |
| Open Graph / Twitter | `og:type`, `og:site_name`, `og:title`, `og:description`, `og:url`, `og:locale` (+ alternate), `og:image` (absolute, 1200×630 PNG, `/og/<template>-<lang>.png`, templates `home`, `courses`, `course`, `page`), `og:image:alt`, `twitter:card=summary_large_image`. |
| `<html lang>` | `tr` / `en` from the URL. |
| Headings | Exactly one `<h1>` per page; section headings are `<h2>` phrased as questions. |
| Preloads | Public stylesheet (`preload as=style` + `stylesheet`), IBM Plex Sans latin 400 and 600 (`preload as=font`, `crossorigin`). |

The public stylesheet is a file, not inline CSS: the production CSP is `style-src 'self'` (docs/security/HEADERS.md).
