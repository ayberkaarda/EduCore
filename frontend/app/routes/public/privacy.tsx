import type { LoaderFunctionArgs, MetaFunction } from 'react-router'
import { useLoaderData } from 'react-router'
import { Breadcrumbs, ContentSections } from '../../public-site/components'
import { copyFor } from '../../public-site/copy'
import { siteData } from '../../public-site/data.server'
import { localeFromPath } from '../../public-site/site-map.mjs'
import { breadcrumbNode, jsonLdMeta } from '../../seo/jsonld'
import { pageMeta } from '../../seo/meta'
import { pageTitle } from '../../seo/text'

export const handle = { publicSite: true }

export async function loader({ request }: LoaderFunctionArgs) {
  const locale = localeFromPath(new URL(request.url).pathname)
  const { config, facts } = await siteData()
  return { locale, siteUrl: config.siteUrl, facts }
}

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => {
  if (!loaderData) return []
  const { locale, siteUrl, facts } = loaderData
  const copy = copyFor(locale)
  return pageMeta({
    siteUrl,
    locale,
    path: '/privacy',
    title: pageTitle(copy.privacy.title),
    description: copy.privacy.description,
    ogTemplate: 'page',
    jsonLd: jsonLdMeta([
      breadcrumbNode({ siteUrl, locale, facts }, [{ name: copy.ui.nav.home, path: '/' }, { name: copy.privacy.h1, path: '/privacy' }]),
    ]),
  })
}

export default function PublicPrivacy() {
  const { locale } = useLoaderData<typeof loader>()
  const copy = copyFor(locale)
  const t = copy.privacy
  return (
    <main id="main" className="pub-main">
      <Breadcrumbs copy={copy} locale={locale} items={[{ name: copy.ui.nav.home, path: '/' }, { name: t.h1, path: '/privacy' }]} />
      <h1>{t.h1}</h1>
      <p className="pub-lead">{t.lead}</p>
      <ContentSections sections={t.sections} idPrefix="privacy" />
    </main>
  )
}
