import type { LoaderFunctionArgs, MetaFunction } from 'react-router'
import { useLoaderData } from 'react-router'
import { Breadcrumbs } from '../../public-site/components'
import { copyFor } from '../../public-site/copy'
import { courseMetaDescription, courseSummary } from '../../public-site/course-text'
import { courseData } from '../../public-site/data.server'
import { formatDate, isoDate } from '../../public-site/format'
import { coursePath, localeFromPath, localizedPath } from '../../public-site/site-map.mjs'
import { breadcrumbNode, courseNode, jsonLdMeta } from '../../seo/jsonld'
import { pageMeta } from '../../seo/meta'
import { pageTitle } from '../../seo/text'

export const handle = { publicSite: true }

export async function loader({ request, params }: LoaderFunctionArgs) {
  const locale = localeFromPath(new URL(request.url).pathname)
  const { config, facts, course } = await courseData(params.slug ?? '')
  return { locale, siteUrl: config.siteUrl, courseMode: config.courseMode, facts, course }
}

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => {
  if (!loaderData) return []
  const { locale, siteUrl, courseMode, facts, course } = loaderData
  const copy = copyFor(locale)
  const context = { siteUrl, locale, facts }
  return pageMeta({
    siteUrl,
    locale,
    path: coursePath(course.slug),
    title: pageTitle(course.name),
    description: courseMetaDescription(locale, course),
    ogTemplate: 'course',
    ogType: 'article',
    jsonLd: jsonLdMeta([
      courseNode(context, course, courseSummary(locale, course), courseMode),
      breadcrumbNode(context, [
        { name: copy.ui.nav.home, path: '/' },
        { name: copy.courses.h1, path: '/courses' },
        { name: course.name, path: coursePath(course.slug) },
      ]),
    ]),
  })
}

export default function PublicCourse() {
  const { locale, course } = useLoaderData<typeof loader>()
  const copy = copyFor(locale)
  const t = copy.course
  return (
    <main id="main" className="pub-main">
      <Breadcrumbs
        copy={copy}
        locale={locale}
        items={[
          { name: copy.ui.nav.home, path: '/' },
          { name: copy.courses.h1, path: '/courses' },
          { name: course.name, path: coursePath(course.slug) },
        ]}
      />
      <article className="pub-article">
        <h1>{course.name}</h1>
        <p className="pub-lead">{course.description || t.noDescription}</p>
        <dl className="pub-course-fields pub-course-fields-detail">
          <div><dt>{t.term}</dt><dd>{course.term || t.notSet}</dd></div>
          <div><dt>{t.instructor}</dt><dd>{course.instructor || t.notSet}</dd></div>
          <div><dt>{t.updated}</dt><dd><time dateTime={isoDate(course.updatedAt)}>{formatDate(locale, course.updatedAt)}</time></dd></div>
        </dl>
        <section className="pub-section" aria-labelledby="enrol">
          <h2 id="enrol">{t.enrolHeading}</h2>
          <p>{t.enrolText}</p>
          <p className="pub-actions">
            <a className="pub-button pub-button-primary" href="/app/login" rel="nofollow">{copy.ui.signIn}</a>
            <a className="pub-button" href={localizedPath(locale, '/courses')}>{t.backToCatalog}</a>
          </p>
        </section>
      </article>
    </main>
  )
}
