# 0028. Anonymous public course catalog with stable slugs and a catalog revision

- Status: Accepted
- Date: 2026-10-02
- Original decision: D-NEW-40

## Context

Published courses must be visible to anonymous visitors and search engines with stable URLs, cache-friendly
responses and correct sitemap dates. The design has to rule out: lost unpublish under concurrent edits, broken URLs
after a slug change, unique-index races, silently dropped sitemap URLs, primary keys in backfilled slugs, and
`dateModified` moving backwards after an unpublish.

## Decision

- `V40` adds `slug` (nullable, unique, required once published), `description`, `published` (default false) and
  `updated_at` to `course`; `V41` adds `version` (optimistic locking); `V42` adds the single-row
  `catalog_revision`.
- Public routes `GET`/`HEAD` `/api/v1/public/courses`, `/api/v1/public/courses/{slug}`,
  `/api/v1/public/site-facts`, `/sitemap.xml` and `/sitemap-courses-{n}.xml` return record DTOs from JPQL
  projections with a SHA-256 based `ETag` and `Cache-Control: max-age=300, public`. Every `/api/**` response
  carries `X-Robots-Tag: noindex, nofollow`.
- Slugs are immutable while published (409 `course/slug-immutable`); slug conflicts are 409
  `course/slug-taken`; generated slugs are retried in a fresh transaction.
- `/sitemap.xml` becomes a sitemap index above `educore.seo.sitemap-max-urls` (default 45 000).
- `lastmod` of `/` and `/courses` and the site-facts `dateModified` use the catalog revision, which only moves
  forward (also on unpublish and delete). `educore.seo.base-url` must be an http(s) origin, otherwise startup
  fails.

## Consequences

Positive:

- Published URLs stay stable without a redirect table; caches and crawlers get consistent validators and dates.

Negative:

- Renaming the slug of a published course requires unpublishing first.
- Reverting requires dropping `V40`–`V42` together with `publicapi/**`.

## References

- [`src/main/resources/db/migration/V40__course_public_fields.sql`](../../src/main/resources/db/migration/V40__course_public_fields.sql)
- [`src/main/resources/db/migration/V41__course_version.sql`](../../src/main/resources/db/migration/V41__course_version.sql)
- [`src/main/resources/db/migration/V42__catalog_revision.sql`](../../src/main/resources/db/migration/V42__catalog_revision.sql)
- [`src/main/java/com/educore/publicapi/PublicApiController.java`](../../src/main/java/com/educore/publicapi/PublicApiController.java)
- [`src/main/java/com/educore/publicapi/PublicCaching.java`](../../src/main/java/com/educore/publicapi/PublicCaching.java)
- [`src/main/java/com/educore/publicapi/SitemapController.java`](../../src/main/java/com/educore/publicapi/SitemapController.java)
- [`src/main/java/com/educore/course/CourseService.java`](../../src/main/java/com/educore/course/CourseService.java)
- [`src/test/java/com/educore/publicapi/PublicApiIT.java`](../../src/test/java/com/educore/publicapi/PublicApiIT.java)
- [`src/test/java/com/educore/publicapi/SitemapIndexIT.java`](../../src/test/java/com/educore/publicapi/SitemapIndexIT.java)
- [ADR 0003](0003-prerendered-public-site-and-spa.md)
