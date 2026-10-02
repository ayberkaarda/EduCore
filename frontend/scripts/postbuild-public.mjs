// Post-build step for the prerendered public pages (runs before externalize-inline-scripts.mjs).
//
// The public pages are complete static documents: they are not hydrated, so they must not download the
// React/React Router runtime or the /app bundle. For every prerendered HTML file except the SPA shell
// (__spa-fallback.html) this script
//   1. removes every executable <script> and every <link rel="modulepreload"> React Router wrote,
//   2. removes every stylesheet except the public stylesheet (assets/public-<hash>.css),
//   3. adds the catalog search island (app/public-site/islands/catalog-search.ts, compiled here with the
//      TypeScript compiler, content-hashed) to the pages that contain the catalog search form,
//   4. copies the prerendered not-found pages to 404.html and en/404.html for nginx's error_page.
// JSON-LD (<script type="application/ld+json">) is data, not code, and is kept.
import { createHash } from 'node:crypto'
import { copyFile, mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import { join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

const clientPath = fileURLToPath(new URL('../dist/client/', import.meta.url))
const islandSource = fileURLToPath(new URL('../app/public-site/islands/catalog-search.ts', import.meta.url))
const SPA_SHELL = '__spa-fallback.html'
const SCRIPT = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi
const LINK = /<link\b[^>]*>/gi
const PUBLIC_CSS = /^\/assets\/public-[\w-]+\.css$/

function attribute(tag, name) {
  const match = new RegExp(`\\s${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s>]+))`, 'i').exec(tag)
  return match ? (match[1] ?? match[2] ?? match[3]) : undefined
}

function isData(attributes) {
  const type = (attribute(`<x ${attributes}>`, 'type') ?? '').trim().toLowerCase()
  return type === 'application/ld+json' || type === 'application/json'
}

async function htmlFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(entries.map(entry => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return entry.name === 'assets' ? [] : htmlFiles(path)
    return entry.name.endsWith('.html') && entry.name !== SPA_SHELL ? [path] : []
  }))
  return nested.flat()
}

async function buildIsland() {
  const source = await readFile(islandSource, 'utf8')
  const { outputText, diagnostics } = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, removeComments: true },
    reportDiagnostics: true,
    fileName: 'catalog-search.ts',
  })
  if (diagnostics && diagnostics.length > 0) {
    throw new Error(`postbuild-public: catalog-search.ts did not compile: ${ts.flattenDiagnosticMessageText(diagnostics[0].messageText, ' ')}`)
  }
  const code = outputText.replace(/\n{2,}/g, '\n')
  const name = `assets/catalog-search-${createHash('sha256').update(code).digest('hex').slice(0, 16)}.js`
  await writeFile(join(clientPath, name), code, 'utf8')
  return `/${name}`
}

const files = await htmlFiles(clientPath)
if (files.length === 0) {
  console.error(`postbuild-public: no prerendered HTML in ${clientPath}`)
  process.exit(1)
}
const islandUrl = await buildIsland()
let removedScripts = 0
let removedLinks = 0
let islands = 0
for (const file of files) {
  const html = await readFile(file, 'utf8')
  let output = html.replace(SCRIPT, (match, attributes) => {
    if (isData(attributes)) return match
    removedScripts += 1
    return ''
  })
  output = output.replace(LINK, tag => {
    const rel = (attribute(tag, 'rel') ?? '').toLowerCase()
    const href = attribute(tag, 'href') ?? ''
    const stylesheet = rel === 'stylesheet' || (rel === 'preload' && (attribute(tag, 'as') ?? '') === 'style')
    if (rel === 'modulepreload' || (stylesheet && !PUBLIC_CSS.test(href))) {
      removedLinks += 1
      return ''
    }
    return tag
  })
  if (!/<link\b[^>]*rel="stylesheet"[^>]*href="\/assets\/public-[\w-]+\.css"/i.test(output)) {
    console.error(`postbuild-public: ${relative(clientPath, file)} has no public stylesheet`)
    process.exit(1)
  }
  if (/<form\b[^>]*\sdata-catalog-search=/i.test(output)) {
    output = output.replace('</body>', `<script type="module" src="${islandUrl}"></script></body>`)
    islands += 1
  }
  await writeFile(file, output, 'utf8')
}

// nginx error_page targets (docs/seo/SITEMAP.md): /404.html and /en/404.html, without a /404 page of their own.
for (const prefix of ['', `en${sep}`]) {
  const source = join(clientPath, `${prefix}404`, 'index.html')
  await mkdir(join(clientPath, prefix || '.'), { recursive: true })
  await copyFile(source, join(clientPath, `${prefix}404.html`))
  await rm(join(clientPath, `${prefix}404`), { recursive: true, force: true })
}

console.log(`postbuild-public: ${files.length} public page(s): removed ${removedScripts} script(s) and ${removedLinks} link(s), catalog search island ${islandUrl} on ${islands} page(s), 404.html and en/404.html written`)
