import type { Copy, Section } from './copy'
import { SOURCE_REPOSITORY_URL } from './copy'
import { formatDate } from './format'
import type { SiteFacts } from './public-api.mjs'
import { localizedPath, type Locale } from './site-map.mjs'

// Static building blocks of the public pages. They render plain HTML (links are <a href>), because the public
// pages are not hydrated: everything must work with JavaScript disabled.

export function ContentSections({ sections, idPrefix }: { sections: readonly Section[]; idPrefix: string }) {
  return sections.map((section, index) => {
    const id = `${idPrefix}-${index + 1}`
    return (
      <section key={id} className="pub-section" aria-labelledby={id}>
        <h2 id={id}>{section.heading}</h2>
        {section.paragraphs?.map(paragraph => <p key={paragraph}>{paragraph}</p>)}
        {section.items && (
          <ul className="pub-bullets">
            {section.items.map(item => <li key={item}>{item}</li>)}
          </ul>
        )}
      </section>
    )
  })
}

/** The "Facts" block (GEO): one fact per row, the same wording on every page that shows it. */
export function FactsBlock({ copy, locale, facts, courseCount }: { copy: Copy; locale: Locale; facts: SiteFacts; courseCount: number }) {
  const t = copy.ui.facts
  return (
    <section className="pub-facts" aria-labelledby="facts" id="facts-block">
      <h2 id="facts">{t.heading}</h2>
      <dl>
        <div><dt>{t.name}</dt><dd>{facts.name}</dd></div>
        <div><dt>{t.type}</dt><dd>{t.typeValue}</dd></div>
        <div><dt>{t.audience}</dt><dd>{t.audienceValue}</dd></div>
        <div><dt>{t.functions}</dt><dd>{t.functionsValue}</dd></div>
        <div><dt>{t.languages}</dt><dd>{t.languagesValue}</dd></div>
        <div><dt>{t.publishedCourses}</dt><dd className="pub-num">{courseCount}</dd></div>
        <div><dt>{t.source}</dt><dd><a href={SOURCE_REPOSITORY_URL} rel="noopener noreferrer">github.com/ayberkaarda/EduCore</a></dd></div>
        <div>
          <dt>{t.dateModified}</dt>
          <dd>
            {facts.dateModified
              ? <time className="pub-num" dateTime={facts.dateModified}>{formatDate(locale, facts.dateModified)}</time>
              : t.notPublished}
          </dd>
        </div>
      </dl>
    </section>
  )
}

export interface BreadcrumbItem {
  name: string
  /** Locale-neutral path; the last item is the current page and is not a link. */
  path: string
}

export function Breadcrumbs({ copy, locale, items }: { copy: Copy; locale: Locale; items: readonly BreadcrumbItem[] }) {
  return (
    <nav className="pub-breadcrumb" aria-label={copy.ui.breadcrumb}>
      <ol>
        {items.map((item, index) => (
          <li key={item.path}>
            {index === items.length - 1
              ? <span aria-current="page">{item.name}</span>
              : <a href={localizedPath(locale, item.path)}>{item.name}</a>}
          </li>
        ))}
      </ol>
    </nav>
  )
}
