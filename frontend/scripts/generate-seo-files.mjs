// Post-build step: writes the crawler files into dist/client from the backend public API (site facts and the
// published catalog, read again from PUBLIC_API_URL) and from the prerendered pages themselves:
//   robots.txt          allow public paths, disallow /app/ and /api/, one group per AI crawler, Sitemap line
//   sitemap-static.xml  every public URL in both languages with hreflang alternates (nginx fallback for the
//                       backend's /sitemap.xml; docs/seo/SITEMAP.md)
//   llms.txt            H1, summary, last update, About / Courses / Policies / Contact link lists
//   llms-full.txt       every public page as Markdown
// Fails (exit 1) when the configuration is missing, the API is unreachable or a page is missing.
import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'
import { readBuildConfig } from '../app/public-site/build-config.mjs'
import { CONTACT_LINKS } from '../app/public-site/project-links.mjs'
import { assertSameSite, fetchAllCourses, fetchSiteFacts } from '../app/public-site/public-api.mjs'
import { llmsFullTxt, llmsTxt, mainToMarkdown, robotsTxt, sitemapXml } from '../app/public-site/seo-files.mjs'
import { absoluteUrl, coursePath, LOCALES, localizedPath, STATIC_PAGES } from '../app/public-site/site-map.mjs'

const clientPath = fileURLToPath(new URL('../dist/client/', import.meta.url))

/** dist/client file of a prerendered path: "/" -> index.html, "/en/faq" -> en/faq/index.html. */
export function htmlFileFor(path) {
  return path === '/' ? 'index.html' : join(...path.slice(1).split('/'), 'index.html')
}

async function main() {
  const config = readBuildConfig(process.env, 'production')
  const facts = await fetchSiteFacts(config.apiUrl)
  assertSameSite(facts, config.siteUrl)
  const courses = await fetchAllCourses(config.apiUrl)

  const pages = new Map()
  const documents = []
  const neutralPaths = [...STATIC_PAGES, ...courses.map(course => coursePath(course.slug))]
  for (const locale of ['en', ...LOCALES.filter(other => other !== 'en')]) {
    for (const path of neutralPaths) {
      const localPath = localizedPath(locale, path)
      const file = join(clientPath, htmlFileFor(localPath))
      let html
      try {
        html = await readFile(file, 'utf8')
      } catch {
        throw new Error(`generate-seo-files: ${localPath} was not prerendered (${file} is missing).`)
      }
      const { document } = new JSDOM(html).window
      const title = document.title
      const description = document.querySelector('meta[name="description"]')?.getAttribute('content') ?? ''
      const main = document.querySelector('main')
      if (!title || !description || !main) throw new Error(`generate-seo-files: ${localPath} has no title, description or <main>.`)
      pages.set(`${locale}:${path}`, { title, description })
      documents.push({ url: absoluteUrl(config.siteUrl, localPath), locale, markdown: mainToMarkdown(main, config.siteUrl) })
    }
  }

  const outputs = {
    'robots.txt': robotsTxt({ siteUrl: config.siteUrl, aiCrawlers: config.aiCrawlers }),
    'sitemap-static.xml': sitemapXml({ siteUrl: config.siteUrl, facts, courses }),
    'llms.txt': llmsTxt({ siteUrl: config.siteUrl, facts, courses, pages, contact: CONTACT_LINKS }),
    'llms-full.txt': llmsFullTxt({ facts, documents }),
  }
  for (const [name, content] of Object.entries(outputs)) {
    await writeFile(join(clientPath, name), content, 'utf8')
  }
  const sizes = Object.entries(outputs).map(([name, content]) => `${name} ${Buffer.byteLength(content)} B`).join(', ')
  console.log(`generate-seo-files: ${sizes} (${pages.size} pages, ${courses.length} courses, AI crawlers ${config.aiCrawlers.allowPublic ? 'allowed' : 'disallowed'})`)
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch(error => {
    console.error(`\ngenerate-seo-files: BUILD FAILED. ${error instanceof Error ? error.message : String(error)}\n`)
    process.exit(1)
  })
}
