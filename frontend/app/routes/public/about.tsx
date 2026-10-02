import type { LoaderFunctionArgs, MetaFunction } from 'react-router'
import { useLoaderData } from 'react-router'
import { Breadcrumbs, ContentSections, FactsBlock } from '../../public-site/components'
import { copyFor, SOURCE_REPOSITORY_URL } from '../../public-site/copy'
import { siteData } from '../../public-site/data.server'
import { localeFromPath } from '../../public-site/site-map.mjs'
import { breadcrumbNode, jsonLdMeta, organizationNode } from '../../seo/jsonld'
import { pageMeta } from '../../seo/meta'
import { pageTitle } from '../../seo/text'

export const handle = { publicSite: true }

export async function loader({ request }: LoaderFunctionArgs) {
  const locale = localeFromPath(new URL(request.url).pathname)
  const { config, facts, courses } = await siteData()
  return { locale, siteUrl: config.siteUrl, facts, courseCount: courses.length }
}

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => {
  if (!loaderData) return []
  const { locale, siteUrl, facts } = loaderData
  const copy = copyFor(locale)
  const context = { siteUrl, locale, facts }
  return pageMeta({
    siteUrl,
    locale,
    path: '/about',
    title: pageTitle(copy.about.title),
    description: copy.about.description,
    ogTemplate: 'page',
    jsonLd: jsonLdMeta([
      organizationNode(context, [SOURCE_REPOSITORY_URL]),
      breadcrumbNode(context, [{ name: copy.ui.nav.home, path: '/' }, { name: copy.about.h1, path: '/about' }]),
    ]),
  })
}

export default function PublicAbout() {
  const { locale, facts, courseCount } = useLoaderData<typeof loader>()
  const copy = copyFor(locale)
  const t = copy.about
  return (
    <main id="main" className="pub-main">
      <Breadcrumbs copy={copy} locale={locale} items={[{ name: copy.ui.nav.home, path: '/' }, { name: t.h1, path: '/about' }]} />
      <h1>{t.h1}</h1>
      <p className="pub-lead">{t.lead}</p>
      <ContentSections sections={t.sections} idPrefix="about" />
      <FactsBlock copy={copy} locale={locale} facts={facts} courseCount={courseCount} />
    </main>
  )
}
