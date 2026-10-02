// Pure generators for the crawler files written into dist/client at build time by
// scripts/generate-seo-files.mjs: robots.txt, sitemap-static.xml, llms.txt and llms-full.txt.
// No I/O here, so the unit tests exercise exactly the code the build runs. Types: seo-files.d.mts.
import { absoluteUrl, coursePath, DEFAULT_LOCALE, LOCALES, localizedPath, STATIC_PAGES } from './site-map.mjs'

const PRIVATE_PREFIXES = ['/app/', '/api/']

/** robots.txt: public paths allowed, /app/ and /api/ disallowed, one explicit group per AI crawler. */
export function robotsTxt({ siteUrl, aiCrawlers }) {
  const lines = [
    `# robots.txt for ${siteUrl}`,
    '# Generated at build time by scripts/generate-seo-files.mjs; policy in docs/seo/CRAWLERS.md.',
    '',
    'User-agent: *',
    'Allow: /',
    ...PRIVATE_PREFIXES.map(prefix => `Disallow: ${prefix}`),
    '',
    `# AI crawlers (EDUCORE_SEO_AI_CRAWLERS_ALLOW_PUBLIC=${aiCrawlers.allowPublic}): ${aiCrawlers.allowPublic ? 'public pages allowed' : 'no access'}.`,
  ]
  for (const agent of aiCrawlers.userAgents) {
    lines.push(`User-agent: ${agent}`)
    if (aiCrawlers.allowPublic) {
      lines.push('Allow: /', ...PRIVATE_PREFIXES.map(prefix => `Disallow: ${prefix}`))
    } else {
      lines.push('Disallow: /')
    }
    lines.push('')
  }
  lines.push(`Sitemap: ${siteUrl}/sitemap.xml`, '')
  return lines.join('\n')
}

function escapeXml(text) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;')
}

/** W3C date-time in UTC with seconds, as the backend sitemap writes it. */
export function w3cDateTime(iso) {
  return new Date(iso).toISOString().replace(/\.\d{3}Z$/, 'Z')
}

/**
 * The build-time sitemap (served by nginx only when the backend /sitemap.xml is unavailable): every public page in
 * both locales with xhtml:link hreflang alternates. lastmod: the course's updatedAt for course pages, the catalog
 * revision (site facts dateModified) for / and /courses, none for the content pages (no stored date).
 */
export function sitemapXml({ siteUrl, facts, courses }) {
  const entries = []
  const neutral = [
    ...STATIC_PAGES.map(path => ({ path, lastmod: path === '/' || path === '/courses' ? facts.dateModified : null })),
    ...courses.map(course => ({ path: coursePath(course.slug), lastmod: course.updatedAt })),
  ]
  for (const { path, lastmod } of neutral) {
    const alternates = [
      ...LOCALES.map(locale => ({ hreflang: locale, href: absoluteUrl(siteUrl, localizedPath(locale, path)) })),
      { hreflang: 'x-default', href: absoluteUrl(siteUrl, localizedPath(DEFAULT_LOCALE, path)) },
    ]
    for (const locale of LOCALES) {
      const parts = [`<url><loc>${escapeXml(absoluteUrl(siteUrl, localizedPath(locale, path)))}</loc>`]
      if (lastmod) parts.push(`<lastmod>${w3cDateTime(lastmod)}</lastmod>`)
      for (const alternate of alternates) {
        parts.push(`<xhtml:link rel="alternate" hreflang="${alternate.hreflang}" href="${escapeXml(alternate.href)}"/>`)
      }
      parts.push('</url>')
      entries.push(parts.join(''))
    }
  }
  if (entries.length > 50_000) throw new Error(`sitemap-static.xml would list ${entries.length} URLs; the protocol limit is 50,000.`)
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">',
    ...entries,
    '</urlset>',
    '',
  ].join('\n')
}

function oneLine(text) {
  return text.replace(/\s+/g, ' ').trim()
}

/** Escapes Markdown link text. */
function linkText(text) {
  return oneLine(text).replace(/([\\[\]])/g, '\\$1')
}

/**
 * llms.txt (https://llmstxt.org): H1 name, a one-line blockquote summary, the last-updated date, then the About,
 * Courses, Policies and Contact sections as "- [Title](url): summary" lines. Turkish pages are listed under
 * "Optional". `pages` holds the title and meta description of each prerendered page, keyed "<locale>:<path>".
 */
export function llmsTxt({ siteUrl, facts, courses, pages, contact }) {
  const page = (locale, path) => {
    const entry = pages.get(`${locale}:${path}`)
    if (!entry) throw new Error(`llms.txt: no prerendered page for ${locale}:${path}`)
    return `- [${linkText(entry.title)}](${absoluteUrl(siteUrl, localizedPath(locale, path))}): ${oneLine(entry.description)}`
  }
  const course = item => {
    const details = [item.term, item.instructor].filter(Boolean).join(', ')
    const summary = oneLine(item.description) || 'No description published.'
    return `- [${linkText(item.name)}](${absoluteUrl(siteUrl, localizedPath('en', coursePath(item.slug)))}): ${summary}${details ? ` (${details})` : ''}`
  }
  const updated = facts.dateModified ? facts.dateModified.slice(0, 10) : 'not published yet'
  return [
    `# ${facts.name}`,
    '',
    `> ${oneLine(facts.description)}`,
    '',
    `Last updated: ${updated} (catalog revision). Languages: Turkish (default, ${absoluteUrl(siteUrl, '/')}) and English (${absoluteUrl(siteUrl, '/en')}). Full text of every public page: ${siteUrl}/llms-full.txt`,
    '',
    '## About',
    '',
    page('en', '/'),
    page('en', '/about'),
    page('en', '/faq'),
    '',
    '## Courses',
    '',
    page('en', '/courses'),
    ...courses.map(course),
    '',
    '## Policies',
    '',
    page('en', '/privacy'),
    page('en', '/security'),
    '',
    '## Contact',
    '',
    ...contact.map(item => `- [${linkText(item.title)}](${item.url}): ${oneLine(item.summary)}`),
    '',
    '## Optional',
    '',
    ...STATIC_PAGES.map(path => page('tr', path)),
    '',
  ].join('\n')
}

const SKIP_TAGS = new Set(['SCRIPT', 'STYLE', 'FORM', 'NAV', 'TEMPLATE', 'NOSCRIPT', 'PICTURE', 'IMG'])

function inline(node, siteUrl) {
  if (node.nodeType === 3) return node.nodeValue.replace(/\s+/g, ' ')
  if (node.nodeType !== 1 || SKIP_TAGS.has(node.tagName) || node.hasAttribute('hidden')) return ''
  const text = Array.from(node.childNodes).map(child => inline(child, siteUrl)).join('')
  if (node.tagName === 'A') {
    const href = node.getAttribute('href') ?? ''
    const url = href.startsWith('/') ? `${siteUrl}${href}` : href
    return url ? `[${linkText(text)}](${url})` : text
  }
  if (node.tagName === 'STRONG' || node.tagName === 'B') return `**${text.trim()}**`
  return text
}

function block(node, siteUrl, out) {
  if (node.nodeType !== 1 || SKIP_TAGS.has(node.tagName) || node.hasAttribute('hidden')) return
  const text = () => oneLine(inline(node, siteUrl))
  switch (node.tagName) {
    case 'H1': out.push(`# ${text()}`); return
    case 'H2': out.push(`## ${text()}`); return
    case 'H3': out.push(`### ${text()}`); return
    case 'P': { const value = text(); if (value) out.push(value); return }
    case 'UL':
    case 'OL': {
      const items = Array.from(node.children).filter(child => child.tagName === 'LI' && !child.hasAttribute('hidden'))
      const lines = items.map(item => {
        const nested = []
        for (const child of item.children) block(child, siteUrl, nested)
        const flatten = line => line.replace(/^#+ /, '').replace(/^- /gm, '').split('\n').join('; ')
        const content = nested.length > 0 ? nested.map(flatten).join(' — ') : oneLine(inline(item, siteUrl))
        return `- ${content}`
      })
      if (lines.length > 0) out.push(lines.join('\n'))
      return
    }
    case 'DL': {
      const rows = []
      for (const child of node.querySelectorAll('dt')) {
        const dd = child.nextElementSibling
        rows.push(`- ${oneLine(inline(child, siteUrl))}: ${dd ? oneLine(inline(dd, siteUrl)) : ''}`)
      }
      if (rows.length > 0) out.push(rows.join('\n'))
      return
    }
    default:
      for (const child of node.children) block(child, siteUrl, out)
  }
}

/** Markdown of a page's <main> element: headings, paragraphs, lists and definition lists, links made absolute. */
export function mainToMarkdown(main, siteUrl) {
  const out = []
  for (const child of main.children) block(child, siteUrl, out)
  return out.join('\n\n')
}

/** llms-full.txt: every public page as Markdown, English first, each with its canonical source URL. */
export function llmsFullTxt({ facts, documents }) {
  const header = [
    `# ${facts.name}: full text of the public pages`,
    '',
    `> ${oneLine(facts.description)}`,
    '',
    `Generated at build time from the prerendered pages. Catalog last updated: ${facts.dateModified ? facts.dateModified.slice(0, 10) : 'not published yet'}.`,
  ].join('\n')
  const bodies = documents.map(doc => [`---`, '', `Source: ${doc.url}`, `Language: ${doc.locale}`, '', doc.markdown].join('\n'))
  return `${[header, ...bodies].join('\n\n')}\n`
}
