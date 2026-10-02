import { describe, expect, it } from 'vitest'
import { readBuildConfig } from '../app/public-site/build-config.mjs'
import { CONTACT_LINKS } from '../app/public-site/project-links.mjs'
import type { SiteFacts } from '../app/public-site/public-api.mjs'
import { llmsFullTxt, llmsTxt, mainToMarkdown, robotsTxt, sitemapXml } from '../app/public-site/seo-files.mjs'
import { STATIC_PAGES } from '../app/public-site/site-map.mjs'
import { MOCK_COURSES } from '../scripts/mock-public-api.mjs'

const siteUrl = 'https://educore.example.org'
const facts: SiteFacts = {
  name: 'EduCore',
  baseUrl: siteUrl,
  description: 'EduCore is a course and student management platform.',
  languages: ['tr', 'en'],
  dateModified: '2026-09-30T16:05:00.000Z',
}

describe('robots.txt', () => {
  it('allows public paths, disallows /app/ and /api/, has one group per AI crawler and the sitemap', () => {
    const { aiCrawlers } = readBuildConfig({ PUBLIC_API_URL: 'http://localhost:8080', PUBLIC_SITE_URL: siteUrl }, 'production')
    const lines = robotsTxt({ siteUrl, aiCrawlers }).split('\n')
    expect(lines).toEqual(expect.arrayContaining(['User-agent: *', 'Allow: /', 'Disallow: /app/', 'Disallow: /api/', `Sitemap: ${siteUrl}/sitemap.xml`]))
    for (const agent of ['GPTBot', 'ClaudeBot', 'Claude-SearchBot', 'PerplexityBot', 'Google-Extended', 'CCBot']) {
      const start = lines.indexOf(`User-agent: ${agent}`)
      expect(start, agent).toBeGreaterThan(0)
      expect(lines.slice(start + 1, start + 4)).toEqual(['Allow: /', 'Disallow: /app/', 'Disallow: /api/'])
    }
  })

  it('blocks the listed AI crawlers entirely when the policy disallows them', () => {
    const text = robotsTxt({ siteUrl, aiCrawlers: { allowPublic: false, userAgents: ['GPTBot'] } })
    expect(text).toContain('User-agent: GPTBot\nDisallow: /\n')
    expect(text).toContain('User-agent: *\nAllow: /')
  })
})

describe('sitemap-static.xml', () => {
  const xml = sitemapXml({ siteUrl, facts, courses: MOCK_COURSES })

  it('lists every page in both languages with hreflang alternates', () => {
    const urls = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map(match => match[1])
    expect(urls).toHaveLength((STATIC_PAGES.length + MOCK_COURSES.length) * 2)
    expect(urls).toContain(`${siteUrl}/en/courses/dogrusal-cebir`)
    expect(xml).toContain(`<xhtml:link rel="alternate" hreflang="x-default" href="${siteUrl}/courses/dogrusal-cebir"/>`)
    expect(xml).toContain('xmlns:xhtml="http://www.w3.org/1999/xhtml"')
  })

  it('uses updatedAt for courses, the catalog revision for / and /courses, nothing for content pages', () => {
    expect(xml).toMatch(new RegExp(`<loc>${siteUrl}/courses/dogrusal-cebir</loc><lastmod>2026-09-21T10:00:00Z</lastmod>`))
    expect(xml).toContain(`<loc>${siteUrl}/</loc><lastmod>2026-09-30T16:05:00Z</lastmod>`)
    expect(xml).toMatch(new RegExp(`<loc>${siteUrl}/about</loc><xhtml:link`))
  })
})

describe('llms.txt and llms-full.txt', () => {
  const pages = new Map<string, { title: string; description: string }>()
  for (const locale of ['tr', 'en']) {
    for (const path of STATIC_PAGES) pages.set(`${locale}:${path}`, { title: `${locale} ${path} title`, description: `Summary of ${locale} ${path}.` })
  }

  it('has the H1, the blockquote summary, the last update and the four required sections', () => {
    const text = llmsTxt({ siteUrl, facts, courses: MOCK_COURSES, pages, contact: CONTACT_LINKS })
    const lines = text.split('\n')
    expect(lines[0]).toBe('# EduCore')
    expect(lines[2]).toBe('> EduCore is a course and student management platform.')
    expect(lines[4]).toMatch(/^Last updated: 2026-09-30/)
    for (const heading of ['## About', '## Courses', '## Policies', '## Contact']) expect(lines).toContain(heading)
    expect(text).toContain(`- [Doğrusal Cebir](${siteUrl}/en/courses/dogrusal-cebir): Vektör uzayları`)
    expect(text).toContain(`- [en /privacy title](${siteUrl}/en/privacy): Summary of en /privacy.`)
    expect(text).toContain('- [Source repository](https://github.com/ayberkaarda/EduCore)')
  })

  it('converts a page body to Markdown with absolute links and skips forms and navigation', () => {
    const main = new DOMParser().parseFromString(`<main><nav><a href="/">Home</a></nav><h1>Ders kataloğu</h1>
      <p>Bu katalog <a href="/courses/dogrusal-cebir">Doğrusal Cebir</a> dersini listeler.</p>
      <form><input></form><ul><li><h2><a href="/en/faq">FAQ</a></h2><dl><div><dt>Term</dt><dd>2026 Güz</dd></div></dl></li></ul>
      <dl><div><dt>Name</dt><dd>EduCore</dd></div></dl></main>`, 'text/html').querySelector('main') as Element
    const markdown = mainToMarkdown(main, siteUrl)
    expect(markdown).toBe([
      '# Ders kataloğu',
      `Bu katalog [Doğrusal Cebir](${siteUrl}/courses/dogrusal-cebir) dersini listeler.`,
      `- [FAQ](${siteUrl}/en/faq) — Term: 2026 Güz`,
      '- Name: EduCore',
    ].join('\n\n'))
    const full = llmsFullTxt({ facts, documents: [{ url: `${siteUrl}/courses`, locale: 'tr', markdown }] })
    expect(full).toContain(`Source: ${siteUrl}/courses\nLanguage: tr`)
  })
})
