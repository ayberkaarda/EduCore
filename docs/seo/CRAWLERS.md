# Crawler policy: robots.txt, llms.txt, llms-full.txt

All three are generated into `dist/client` at build time by `frontend/scripts/generate-seo-files.mjs`
(pure generators in `app/public-site/seo-files.mjs`, unit-tested in `tests/seo-files.test.ts`). They are not
committed: their content depends on the deployment origin and the published catalog.

## robots.txt

```
User-agent: *
Allow: /
Disallow: /app/
Disallow: /api/

User-agent: GPTBot          (one explicit group per configured AI crawler)
Allow: /
Disallow: /app/
Disallow: /api/
...
Sitemap: <EDUCORE_SEO_BASE_URL>/sitemap.xml
```

The AI crawler list and policy mirror the backend's `educore.seo.ai-crawlers` through the same environment
variables: `EDUCORE_SEO_AI_CRAWLERS_USER_AGENTS` (default `GPTBot, ClaudeBot, Claude-SearchBot, PerplexityBot,
Google-Extended, CCBot`) and `EDUCORE_SEO_AI_CRAWLERS_ALLOW_PUBLIC` (default `true`). With `false`, each listed
agent gets `Disallow: /`. Public paths are allowed by default for every other crawler. robots.txt is advisory;
`/app/**` additionally carries `noindex` (meta and `X-Robots-Tag`) and `/api/**` `X-Robots-Tag: noindex, nofollow`.

## llms.txt

H1 (site-facts `name`), one-line blockquote (site-facts `description`), last update (catalog revision), then
`## About`, `## Courses` (catalog page + every published course, English URLs, description, term and
instructor), `## Policies` (privacy, security), `## Contact` (source repository, private vulnerability report)
and `## Optional` (Turkish pages). Each line is `- [Title](absolute url): summary`; titles and summaries are the
pages' own `<title>` and meta description, so every fact is stated with the same words as on the page.

## llms-full.txt

Every indexable public page (English first, then Turkish) converted from its prerendered `<main>` to Markdown,
each preceded by `Source: <canonical url>` and `Language:`. Navigation and the search form are left out.
