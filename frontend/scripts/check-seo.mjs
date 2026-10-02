// Build gate for the public site (last step of `npm run build`). For every expected public URL (static pages and
// every published course from PUBLIC_API_URL, in both languages, plus the 404 pages) it reads the prerendered HTML
// and checks: <html lang>, one <title> of at most 60 characters, a unique description of 140-160 characters, the
// absolute canonical URL, hreflang tr/en/x-default, robots meta, Open Graph image (file exists), one H1, JSON-LD
// (docs/seo/STRUCTURED_DATA.md), that no script other than the catalog search island is loaded, and the JS budget
// (60 KB gzip per public page). It also checks robots.txt, sitemap-static.xml, llms.txt, llms-full.txt and the
// noindex of the SPA shell. Prints one line per page; exits 1 on any problem.
import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'
import { JSDOM } from 'jsdom'
import { readBuildConfig } from '../app/public-site/build-config.mjs'
import { assertSameSite, fetchAllCourses, fetchSiteFacts } from '../app/public-site/public-api.mjs'
import { absoluteUrl, coursePath, DEFAULT_LOCALE, LOCALES, localizedPath, NOT_FOUND_PAGE, STATIC_PAGES } from '../app/public-site/site-map.mjs'
import { pageKind, validateJsonLdBlocks } from '../app/seo/jsonld-rules.mjs'

const clientPath = fileURLToPath(new URL('../dist/client/', import.meta.url))
const JS_BUDGET_GZ = 60 * 1024
const TOTAL_BUDGET_GZ = 300 * 1024
const ISLAND = /^\/assets\/catalog-search-[0-9a-f]{16}\.js$/

const problems = []
const fail = message => problems.push(message)

function fileFor(path) {
  if (path === NOT_FOUND_PAGE) return '404.html'
  if (path === `/en${NOT_FOUND_PAGE}`) return join('en', '404.html')
  return path === '/' ? 'index.html' : join(...path.slice(1).split('/'), 'index.html')
}

const gzCache = new Map()
async function gzSize(urlPath) {
  if (!gzCache.has(urlPath)) {
    const file = join(clientPath, ...urlPath.slice(1).split('/'))
    if (!existsSync(file)) {
      fail(`${urlPath} is referenced but missing from dist/client`)
      gzCache.set(urlPath, 0)
    } else {
      gzCache.set(urlPath, gzipSync(await readFile(file), { level: 9 }).length)
    }
  }
  return gzCache.get(urlPath)
}

function kb(bytes) {
  return `${(bytes / 1024).toFixed(1)} KB`
}

async function checkPage({ path, neutral, locale, siteUrl, notFound, titles, descriptions }) {
  const file = join(clientPath, fileFor(path))
  if (!existsSync(file)) {
    fail(`${path}: not prerendered (${fileFor(path)} missing)`)
    return null
  }
  const html = await readFile(file, 'utf8')
  const { document } = new JSDOM(html).window
  const where = path
  const head = document.head

  if (document.documentElement.lang !== locale) fail(`${where}: <html lang="${document.documentElement.lang}">, expected ${locale}`)
  const titleElements = document.querySelectorAll('title')
  const title = document.title
  if (titleElements.length !== 1) fail(`${where}: ${titleElements.length} <title> elements`)
  if (!title || title.length > 60) fail(`${where}: title "${title}" is empty or longer than 60 characters (${title.length})`)
  // Unique within one language: the Turkish and English page of a course may both carry the course name.
  const titleKey = `${locale}|${title}`
  if (titles.has(titleKey)) fail(`${where}: title "${title}" is also used by ${titles.get(titleKey)}`)
  titles.set(titleKey, path)

  const descriptionTags = head.querySelectorAll('meta[name="description"]')
  const description = descriptionTags[0]?.getAttribute('content') ?? ''
  if (descriptionTags.length !== 1) fail(`${where}: ${descriptionTags.length} meta descriptions`)
  if (description.length < 140 || description.length > 160) fail(`${where}: description has ${description.length} characters (140-160)`)
  if (descriptions.has(description)) fail(`${where}: description is also used by ${descriptions.get(description)}`)
  descriptions.set(description, path)

  const robots = head.querySelector('meta[name="robots"]')?.getAttribute('content') ?? ''
  const h1s = document.querySelectorAll('h1')
  if (h1s.length !== 1) fail(`${where}: ${h1s.length} <h1> elements`)
  if (!document.querySelector('main')) fail(`${where}: no <main>`)

  const canonical = head.querySelector('link[rel="canonical"]')?.getAttribute('href') ?? ''
  const alternates = new Map([...head.querySelectorAll('link[rel="alternate"][hreflang]')].map(link => [link.getAttribute('hreflang'), link.getAttribute('href')]))
  const ldBlocks = [...document.querySelectorAll('script[type="application/ld+json"]')]
  const rawLd = ldBlocks.map(block => block.textContent ?? '')
  let types = []

  if (notFound) {
    if (!robots.startsWith('noindex')) fail(`${where}: the not-found page must be noindex`)
    if (canonical) fail(`${where}: the not-found page must not have a canonical URL`)
  } else {
    if (robots !== 'index, follow') fail(`${where}: robots meta is "${robots}"`)
    const expected = absoluteUrl(siteUrl, path)
    if (canonical !== expected) fail(`${where}: canonical "${canonical}", expected "${expected}"`)
    for (const other of LOCALES) {
      const href = absoluteUrl(siteUrl, localizedPath(other, neutral))
      if (alternates.get(other) !== href) fail(`${where}: hreflang ${other} is "${alternates.get(other)}", expected "${href}"`)
    }
    const xDefault = absoluteUrl(siteUrl, localizedPath(DEFAULT_LOCALE, neutral))
    if (alternates.get('x-default') !== xDefault) fail(`${where}: hreflang x-default is "${alternates.get('x-default')}"`)
    const ogImage = head.querySelector('meta[property="og:image"]')?.getAttribute('content') ?? ''
    if (!ogImage.startsWith(`${siteUrl}/og/`) || !existsSync(join(clientPath, ...ogImage.slice(siteUrl.length + 1).split('/')))) {
      fail(`${where}: og:image "${ogImage}" is not an existing /og/ file`)
    }
    for (const property of ['og:title', 'og:description', 'og:url', 'og:type']) {
      if (!head.querySelector(`meta[property="${property}"]`)) fail(`${where}: ${property} missing`)
    }
    if (head.querySelector('meta[name="twitter:card"]')?.getAttribute('content') !== 'summary_large_image') fail(`${where}: twitter:card missing`)
    const result = validateJsonLdBlocks(rawLd, pageKind(neutral), where)
    result.problems.forEach(fail)
    types = result.types
  }

  // Scripts: only the island (and only on catalog pages); no modulepreload; JSON-LD is data.
  const scripts = [...document.querySelectorAll('script')].filter(script => script.getAttribute('type') !== 'application/ld+json')
  for (const script of scripts) {
    const src = script.getAttribute('src') ?? ''
    if (!ISLAND.test(src)) fail(`${where}: unexpected script ${src || '(inline)'}`)
    else if (neutral !== '/courses') fail(`${where}: the catalog search island is only for /courses`)
  }
  if (document.querySelector('link[rel="modulepreload"]')) fail(`${where}: modulepreload links (the app bundle) must not be on public pages`)
  const jsUrls = scripts.map(script => script.getAttribute('src')).filter(Boolean)
  const cssUrls = [...head.querySelectorAll('link[rel="stylesheet"]')].map(link => link.getAttribute('href'))
  const fontUrls = [...head.querySelectorAll('link[rel="preload"][as="font"]')].map(link => link.getAttribute('href'))
  let js = 0
  for (const url of jsUrls) js += await gzSize(url)
  let total = gzipSync(html, { level: 9 }).length + js
  for (const url of cssUrls) total += await gzSize(url)
  for (const url of fontUrls) total += await gzSize(url)
  if (js > JS_BUDGET_GZ) fail(`${where}: ${kb(js)} JavaScript (gzip) exceeds the 60 KB budget`)
  if (total > TOTAL_BUDGET_GZ) fail(`${where}: ${kb(total)} initial transfer (gzip) exceeds the 300 KB budget`)

  // Readable without JavaScript: real text in <main> and plain links in the navigation.
  const text = (document.querySelector('main')?.textContent ?? '').replace(/\s+/g, ' ').trim()
  if (text.length < 120) fail(`${where}: <main> has only ${text.length} characters of text`)
  if (document.querySelectorAll('header a[href], footer a[href]').length < 6) fail(`${where}: header and footer links are missing`)
  return { path, title, canonical: canonical || '-', hreflang: alternates.size, jsonld: types.join('+') || '-', js, total }
}

async function main() {
  const config = readBuildConfig(process.env, 'production')
  const facts = await fetchSiteFacts(config.apiUrl)
  assertSameSite(facts, config.siteUrl)
  const courses = await fetchAllCourses(config.apiUrl)
  const titles = new Map()
  const descriptions = new Map()
  const rows = []
  const neutralPaths = [...STATIC_PAGES, ...courses.map(course => coursePath(course.slug))]
  for (const locale of LOCALES) {
    for (const neutral of [...neutralPaths, NOT_FOUND_PAGE]) {
      const path = localizedPath(locale, neutral)
      const row = await checkPage({ path, neutral, locale, siteUrl: config.siteUrl, notFound: neutral === NOT_FOUND_PAGE, titles, descriptions })
      if (row) rows.push(row)
    }
  }

  // Crawler files.
  const read = async name => (existsSync(join(clientPath, name)) ? readFile(join(clientPath, name), 'utf8') : (fail(`${name} missing from dist/client`), ''))
  const robots = await read('robots.txt')
  for (const line of ['User-agent: *', 'Disallow: /app/', 'Disallow: /api/', `Sitemap: ${config.siteUrl}/sitemap.xml`]) {
    if (!robots.split('\n').includes(line)) fail(`robots.txt: missing "${line}"`)
  }
  for (const agent of config.aiCrawlers.userAgents) {
    if (!robots.split('\n').includes(`User-agent: ${agent}`)) fail(`robots.txt: no group for ${agent}`)
  }
  const sitemap = await read('sitemap-static.xml')
  for (const locale of LOCALES) {
    for (const neutral of neutralPaths) {
      const loc = absoluteUrl(config.siteUrl, localizedPath(locale, neutral))
      if (!sitemap.includes(`<loc>${loc}</loc>`)) fail(`sitemap-static.xml: ${loc} missing`)
    }
  }
  const llms = await read('llms.txt')
  for (const heading of [`# ${facts.name}`, '## About', '## Courses', '## Policies', '## Contact']) {
    if (!llms.split('\n').includes(heading)) fail(`llms.txt: missing "${heading}"`)
  }
  if (!/^> .+/m.test(llms)) fail('llms.txt: missing the blockquote summary')
  for (const course of courses) {
    if (!llms.includes(absoluteUrl(config.siteUrl, localizedPath('en', coursePath(course.slug))))) fail(`llms.txt: course ${course.slug} missing`)
  }
  const llmsFull = await read('llms-full.txt')
  if (llmsFull.split('\nSource: ').length - 1 !== neutralPaths.length * LOCALES.length) fail('llms-full.txt: not every public page is included')
  const shell = await read('__spa-fallback.html')
  if (!/<meta name="robots" content="noindex, nofollow"/.test(shell)) fail('__spa-fallback.html: the /app shell must carry robots noindex, nofollow')
  if (!/<html lang="en"/.test(shell)) fail('__spa-fallback.html: <html lang="en"> expected')

  console.log('check-seo: path | title | canonical | hreflang | JSON-LD | JS gz | initial gz')
  for (const row of rows) {
    console.log(`  ${row.path} | ${row.title} | ${row.canonical} | ${row.hreflang} | ${row.jsonld} | ${kb(row.js)} | ${kb(row.total)}`)
  }
  const maxJs = Math.max(...rows.map(row => row.js))
  const maxTotal = Math.max(...rows.map(row => row.total))
  console.log(`check-seo: ${rows.length} pages, max JS ${kb(maxJs)} gzip (budget 60 KB), max initial transfer ${kb(maxTotal)} gzip (budget 300 KB)`)
  if (problems.length > 0) {
    console.error(`\ncheck-seo: ${problems.length} problem(s):`)
    for (const problem of problems) console.error(`  - ${problem}`)
    process.exit(1)
  }
  console.log('check-seo: OK')
}

main().catch(error => {
  console.error(`\ncheck-seo: FAILED. ${error instanceof Error ? error.message : String(error)}\n`)
  process.exit(1)
})
