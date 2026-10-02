# Structured data (JSON-LD)

One `<script type="application/ld+json">` per indexable page, an `@graph` built by
`frontend/app/seo/jsonld.tsx` (typed with `schema-dts`).

| Page | Nodes |
|---|---|
| `/`, `/en` | `Organization` {`@id`, name, url, logo (`/brand/logo-512.png`), description, sameAs [source repository]}, `WebSite` {url, name, description, inLanguage, publisher, dateModified = catalog revision} |
| `/courses` | `ItemList` {numberOfItems, itemListElement: `ListItem` → `Course` {name, description, url, provider}}, `BreadcrumbList` |
| `/courses/<slug>` | `Course` {name, description, url, provider, dateModified, hasCourseInstance: `CourseInstance` {courseMode, name = term, instructor: `Person` {name}}}, `BreadcrumbList` |
| `/faq` | `FAQPage` {mainEntity: `Question` {name, acceptedAnswer: `Answer` {text}}} built from the same entries the page shows, `BreadcrumbList` |
| `/about` | `Organization`, `BreadcrumbList` |
| `/privacy`, `/security` | `BreadcrumbList` |
| 404 | none |

- **No student data.** The only inputs are `SiteFacts` and `PublicCourse` (name, slug, term, instructor,
  description, updatedAt), which the backend `PublicApiLeakIT` pins. `app/seo/jsonld-rules.mjs` additionally
  rejects any property outside an allow-list, so a new field cannot slip in unnoticed.
- **courseMode.** The API has no per-course teaching mode; `PUBLIC_COURSE_MODE` (default `Onsite`) applies to all
  courses. A per-course field is a backend change (BACKLOG).
- **Course without description.** schema.org `Course` needs one; a neutral sentence ("A course published in the
  EduCore catalog.") is used, and the page itself says that no description was published.
- **Escaping.** React Router's `<Meta>` serialises with `JSON.stringify` and escapes `<`, `>`, `&`, U+2028,
  U+2029. The mock course `web-guvenligi` has `</script><script>alert(1)</script>` in its description;
  `tests/prerender/pages.check.ts` asserts it appears only as `</script>...` in the JSON-LD and as
  `&lt;/script&gt;` in the body, and that the page has no other script.
- **Validation.** `tests/jsonld.test.ts` (builders, round trip through an HTML parser, rejection cases) and
  `tests/prerender/pages.check.ts` + `scripts/check-seo.mjs` (every prerendered page) use the same rules:
  exactly one block, valid JSON, `@context` https://schema.org, required types and properties, absolute URLs.
