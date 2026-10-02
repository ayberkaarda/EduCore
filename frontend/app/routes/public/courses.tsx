import type { LoaderFunctionArgs, MetaFunction } from 'react-router'
import { useLoaderData } from 'react-router'
import { Breadcrumbs } from '../../public-site/components'
import { copyFor } from '../../public-site/copy'
import { courseSummary } from '../../public-site/course-text'
import { siteData } from '../../public-site/data.server'
import { formatDate, isoDate } from '../../public-site/format'
import { coursePath, localeFromPath, localizedPath } from '../../public-site/site-map.mjs'
import { breadcrumbNode, itemListNode, jsonLdMeta } from '../../seo/jsonld'
import { pageMeta } from '../../seo/meta'
import { pageTitle } from '../../seo/text'

export const handle = { publicSite: true }

export async function loader({ request }: LoaderFunctionArgs) {
  const locale = localeFromPath(new URL(request.url).pathname)
  const { config, facts, courses } = await siteData()
  return { locale, siteUrl: config.siteUrl, facts, courses }
}

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => {
  if (!loaderData) return []
  const { locale, siteUrl, facts, courses } = loaderData
  const copy = copyFor(locale)
  const t = copy.courses
  const context = { siteUrl, locale, facts }
  return pageMeta({
    siteUrl,
    locale,
    path: '/courses',
    title: pageTitle(t.title),
    description: t.description(courses.length),
    ogTemplate: 'courses',
    jsonLd: jsonLdMeta([
      itemListNode(context, courses, course => courseSummary(locale, course)),
      breadcrumbNode(context, [{ name: copy.ui.nav.home, path: '/' }, { name: t.h1, path: '/courses' }]),
    ]),
  })
}

export default function PublicCourses() {
  const { locale, courses } = useLoaderData<typeof loader>()
  const copy = copyFor(locale)
  const t = copy.courses
  return (
    <main id="main" className="pub-main">
      <Breadcrumbs copy={copy} locale={locale} items={[{ name: copy.ui.nav.home, path: '/' }, { name: t.h1, path: '/courses' }]} />
      <h1>{t.h1}</h1>
      <p className="pub-lead">{t.lead(courses.length)}</p>
      {/* Catalog search island (app/public-site/islands/catalog-search.ts): hidden without JavaScript; the
          reserved block height keeps the list from moving when the script reveals it. */}
      <div className="pub-search-slot">
        <form className="pub-search" role="search" hidden data-catalog-search="" data-no-match={t.noMatch}>
          <label htmlFor="catalog-search">{t.searchLabel}</label>
          <input id="catalog-search" type="search" autoComplete="off" spellCheck={false} aria-describedby="catalog-search-hint" />
          <span id="catalog-search-hint" className="pub-hint">{t.searchHint}</span>
        </form>
      </div>
      <p className="pub-no-match" role="status" aria-live="polite" data-catalog-status="" />
      <ul className="pub-course-list" aria-label={t.listLabel} data-catalog-list="">
        {courses.map(course => (
          <li key={course.slug} className="pub-course">
            <h2><a href={localizedPath(locale, coursePath(course.slug))}>{course.name}</a></h2>
            <dl className="pub-course-fields">
              {course.term && <div><dt>{t.term}</dt><dd>{course.term}</dd></div>}
              {course.instructor && <div><dt>{t.instructor}</dt><dd>{course.instructor}</dd></div>}
              <div><dt>{t.updated}</dt><dd><time dateTime={isoDate(course.updatedAt)}>{formatDate(locale, course.updatedAt)}</time></dd></div>
            </dl>
            {course.description && <p>{course.description}</p>}
          </li>
        ))}
      </ul>
    </main>
  )
}
