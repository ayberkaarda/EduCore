import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { describe, expect, it } from 'vitest'
import { pageKind, validateJsonLdBlocks } from '../../app/seo/jsonld-rules.mjs'
import { localeFromPath, neutralPath } from '../../app/public-site/site-map.mjs'

// Parses every prerendered public page in dist/client (the HTML a crawler or a browser without JavaScript gets)
// and validates its content, headings, links and JSON-LD block.
// The jsdom environment gives modules a non-file import.meta.url; the config's root is the frontend directory.
const client = join(process.cwd(), 'dist', 'client')

function htmlFiles(directory: string): string[] {
  return readdirSync(directory).flatMap(name => {
    const path = join(directory, name)
    if (statSync(path).isDirectory()) return name === 'assets' ? [] : htmlFiles(path)
    return name.endsWith('.html') && name !== '__spa-fallback.html' ? [path] : []
  })
}

function urlPath(file: string): string {
  const rel = relative(client, file).split(sep).join('/')
  if (rel === 'index.html') return '/'
  if (rel.endsWith('404.html')) return `/${rel.replace(/\.html$/, '')}`
  return `/${rel.replace(/\/index\.html$/, '')}`
}

const pages = existsSync(client) ? htmlFiles(client).map(file => {
  const html = readFileSync(file, 'utf8')
  return { file, path: urlPath(file), html, document: new DOMParser().parseFromString(html, 'text/html') }
}) : []

describe('prerendered public pages (dist/client)', () => {
  it('exist for both languages', () => {
    expect(existsSync(client), 'run a build first (npm run build:mock)').toBe(true)
    const paths = pages.map(page => page.path)
    for (const path of ['/', '/courses', '/about', '/faq', '/privacy', '/security', '/404', '/en', '/en/courses', '/en/about', '/en/faq', '/en/privacy', '/en/security', '/en/404']) {
      expect(paths, path).toContain(path)
    }
    expect(paths.filter(path => /^\/(en\/)?courses\/[a-z0-9-]+$/.test(path)).length).toBeGreaterThanOrEqual(2)
  })

  it('are readable without JavaScript: one H1, text in <main>, plain navigation links, the right language', () => {
    for (const { path, document } of pages) {
      expect(document.querySelectorAll('h1'), path).toHaveLength(1)
      expect(document.querySelector('h1')?.textContent?.trim().length, path).toBeGreaterThan(3)
      expect((document.querySelector('main')?.textContent ?? '').trim().length, path).toBeGreaterThan(120)
      const links = [...document.querySelectorAll('header a[href]')].map(link => link.getAttribute('href'))
      const locale = localeFromPath(path)
      expect(links, path).toEqual(expect.arrayContaining([locale === 'en' ? '/en/courses' : '/courses', locale === 'en' ? '/en' : '/', '/app/login']))
      expect(document.documentElement.lang, path).toBe(locale)
      expect(document.querySelector('a[hreflang="tr"][lang="tr"]'), path).not.toBeNull()
      expect(document.querySelector('a[hreflang="en"][lang="en"]'), path).not.toBeNull()
    }
  })

  it('load no application JavaScript; only the catalog pages load the search island', () => {
    for (const { path, document } of pages) {
      const scripts = [...document.querySelectorAll('script')].filter(script => script.type !== 'application/ld+json')
      const expected = neutralPath(path) === '/courses' ? 1 : 0
      expect(scripts, path).toHaveLength(expected)
      if (expected) expect(scripts[0].getAttribute('src')).toMatch(/^\/assets\/catalog-search-[0-9a-f]{16}\.js$/)
      expect(document.querySelector('link[rel="modulepreload"]'), path).toBeNull()
      expect([...document.querySelectorAll('link[rel="stylesheet"]')].map(link => link.getAttribute('href')), path)
        .toEqual([expect.stringMatching(/^\/assets\/public-[\w-]+\.css$/)])
    }
  })

  it('carry one valid JSON-LD block per indexable page', () => {
    for (const { path, document } of pages) {
      const blocks = [...document.querySelectorAll('script[type="application/ld+json"]')].map(script => script.textContent ?? '')
      if (path.endsWith('/404')) {
        expect(blocks, path).toHaveLength(0)
        continue
      }
      const { problems } = validateJsonLdBlocks(blocks, pageKind(neutralPath(path)), path)
      expect(problems, path).toEqual([])
    }
  })

  it('escape hostile course text in JSON-LD and in the page body', () => {
    const page = pages.find(entry => entry.path === '/courses/web-guvenligi')
    expect(page, 'the mock course web-guvenligi has "</script>" in its description').toBeDefined()
    const { html, document } = page!
    const ld = html.slice(html.indexOf('application/ld+json'))
    expect(ld.slice(0, ld.indexOf('</script>'))).toContain('\\u003c/script\\u003e\\u003cscript\\u003ealert(1)')
    expect(html).toContain('&lt;/script&gt;&lt;script&gt;alert(1)&lt;/script&gt;')
    expect(document.querySelectorAll('script').length).toBe(1)
    expect(document.querySelector('.pub-lead')?.textContent).toContain('</script><script>alert(1)</script>')
  })

  it('show the Facts block with a machine-readable dateModified on landing, about and FAQ', () => {
    for (const path of ['/', '/about', '/faq', '/en', '/en/about', '/en/faq']) {
      const { document } = pages.find(entry => entry.path === path)!
      const time = document.querySelector('#facts-block time[datetime]')
      expect(time, path).not.toBeNull()
      expect(Number.isNaN(Date.parse(time!.getAttribute('datetime')!)), path).toBe(false)
    }
  })

  it('list exactly the courses of the ItemList on the catalog page', () => {
    for (const path of ['/courses', '/en/courses']) {
      const { document } = pages.find(entry => entry.path === path)!
      const ld = JSON.parse(document.querySelector('script[type="application/ld+json"]')!.textContent!)
      const listed = [...document.querySelectorAll('[data-catalog-list] > li h2 a')].map(link => link.getAttribute('href'))
      const structured = ld['@graph'][0].itemListElement.map((element: { item: { url: string } }) => new URL(element.item.url).pathname)
      expect(listed, path).toEqual(structured)
    }
  })
})
