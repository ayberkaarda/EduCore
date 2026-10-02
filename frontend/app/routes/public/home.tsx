import type { LoaderFunctionArgs, MetaFunction } from 'react-router'
import { useLoaderData } from 'react-router'
import { ContentSections, FactsBlock } from '../../public-site/components'
import { copyFor, SOURCE_REPOSITORY_URL } from '../../public-site/copy'
import { siteData } from '../../public-site/data.server'
import { formatDate, isoDate } from '../../public-site/format'
import { coursePath, localeFromPath, localizedPath } from '../../public-site/site-map.mjs'
import { jsonLdMeta, organizationNode, websiteNode } from '../../seo/jsonld'
import { pageMeta } from '../../seo/meta'

export const handle = { publicSite: true }

const RECENT_COUNT = 3

export async function loader({ request }: LoaderFunctionArgs) {
  const locale = localeFromPath(new URL(request.url).pathname)
  const { config, facts, courses } = await siteData()
  const recent = [...courses].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt) || a.name.localeCompare(b.name)).slice(0, RECENT_COUNT)
  return { locale, siteUrl: config.siteUrl, facts, courseCount: courses.length, recent }
}

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => {
  if (!loaderData) return []
  const { locale, siteUrl, facts } = loaderData
  const t = copyFor(locale).home
  const context = { siteUrl, locale, facts }
  return pageMeta({
    siteUrl,
    locale,
    path: '/',
    title: t.title,
    description: t.description,
    ogTemplate: 'home',
    jsonLd: jsonLdMeta([organizationNode(context, [SOURCE_REPOSITORY_URL]), websiteNode(context)]),
  })
}

export default function PublicHome() {
  const { locale, facts, courseCount, recent } = useLoaderData<typeof loader>()
  const copy = copyFor(locale)
  const t = copy.home
  return (
    <main id="main" className="pub-main">
      <div className="pub-hero">
        <h1>{t.h1}</h1>
        <p className="pub-lead">{t.lead}</p>
        <p className="pub-actions">
          <a className="pub-button pub-button-primary" href={localizedPath(locale, '/courses')}>{t.catalogLink}</a>
          <a className="pub-button" href={localizedPath(locale, '/about')}>{copy.ui.nav.about}</a>
        </p>
      </div>
      <ContentSections sections={t.sections} idPrefix="home" />
      <section className="pub-section" aria-labelledby="home-catalog">
        <h2 id="home-catalog">{t.catalogHeading}</h2>
        <p>{t.catalogIntro(courseCount)}</p>
        <ul className="pub-course-list">
          {recent.map(course => (
            <li key={course.slug} className="pub-course">
              <h3><a href={localizedPath(locale, coursePath(course.slug))}>{course.name}</a></h3>
              <p className="pub-course-meta">
                {[course.term, course.instructor].filter(Boolean).join(' · ')}
                {' · '}
                <time dateTime={isoDate(course.updatedAt)}>{formatDate(locale, course.updatedAt)}</time>
              </p>
            </li>
          ))}
        </ul>
        <p><a href={localizedPath(locale, '/courses')}>{t.catalogLink}</a></p>
      </section>
      <FactsBlock copy={copy} locale={locale} facts={facts} courseCount={courseCount} />
    </main>
  )
}
