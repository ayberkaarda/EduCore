import type { LoaderFunctionArgs, MetaFunction } from 'react-router'
import { useLoaderData } from 'react-router'
import { Breadcrumbs, FactsBlock } from '../../public-site/components'
import { copyFor } from '../../public-site/copy'
import { siteData } from '../../public-site/data.server'
import { localeFromPath } from '../../public-site/site-map.mjs'
import { breadcrumbNode, faqNode, jsonLdMeta } from '../../seo/jsonld'
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
    path: '/faq',
    title: pageTitle(copy.faq.title),
    description: copy.faq.description,
    ogTemplate: 'page',
    jsonLd: jsonLdMeta([
      faqNode(context, copy.faq.entries),
      breadcrumbNode(context, [{ name: copy.ui.nav.home, path: '/' }, { name: copy.faq.h1, path: '/faq' }]),
    ]),
  })
}

export default function PublicFaq() {
  const { locale, facts, courseCount } = useLoaderData<typeof loader>()
  const copy = copyFor(locale)
  const t = copy.faq
  return (
    <main id="main" className="pub-main">
      <Breadcrumbs copy={copy} locale={locale} items={[{ name: copy.ui.nav.home, path: '/' }, { name: t.h1, path: '/faq' }]} />
      <h1>{t.h1}</h1>
      <p className="pub-lead">{t.lead}</p>
      {t.entries.map((entry, index) => (
        <section key={entry.question} className="pub-section pub-faq" aria-labelledby={`faq-${index + 1}`}>
          <h2 id={`faq-${index + 1}`}>{entry.question}</h2>
          <p>{entry.answer}</p>
        </section>
      ))}
      <FactsBlock copy={copy} locale={locale} facts={facts} courseCount={courseCount} />
    </main>
  )
}
