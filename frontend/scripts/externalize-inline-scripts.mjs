// Post-build step: moves every inline <script> of the generated HTML files into a same-origin file, so the
// Content-Security-Policy can use `script-src 'self'` without 'unsafe-inline', nonces or hashes.
//
// React Router writes inline scripts into the prerendered pages (route context, module bootstrap, React's
// streaming helpers). An external classic script without async/defer executes at the same point of parsing as
// the inline one it replaces, and an external module script keeps its type and async attributes, so the
// execution order is unchanged. Files are named by content hash; identical scripts are written once.
// Dry-run: build pages containing executable classic/module scripts plus application/json,
// application/ld+json and importmap blocks; run this script twice. Only executable bodies
// should become assets and the second run should replace zero scripts. Import maps have no
// browser-supported src form: deployments using them must permit their inline body with a
// CSP nonce/hash. Inert data and import maps are excluded from the executable-inline check.
import { createHash } from 'node:crypto'
import { readdir, readFile, writeFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const clientPath = fileURLToPath(new URL('../dist/client/', import.meta.url))
const ASSET_DIR = 'assets'
const INLINE_SCRIPT = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi
function executable(attributes) {
  const type = /(?:^|\s)type\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i.exec(attributes)
  const value = (type?.[1] ?? type?.[2] ?? type?.[3] ?? '').trim().toLowerCase()
  return value === '' || value === 'module'
    || /^(?:text|application)\/(?:x-)?(?:java|ecma)script$/.test(value)
    || /^text\/(?:javascript1\.[0-5]|jscript|livescript)$/.test(value)
}

async function htmlFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(entries.map(entry => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return entry.name === ASSET_DIR ? [] : htmlFiles(path)
    return entry.name.endsWith('.html') ? [path] : []
  }))
  return nested.flat()
}

const files = await htmlFiles(clientPath)
if (files.length === 0) {
  console.error(`externalize-inline-scripts: no HTML files in ${clientPath}`)
  process.exit(1)
}

const written = new Set()
let replaced = 0
for (const file of files) {
  const html = await readFile(file, 'utf8')
  const pending = []
  const output = html.replace(INLINE_SCRIPT, (match, attributes, body) => {
    if (/(?:^|\s)src\s*=/i.test(attributes) || body.trim() === '') return match
    if (!executable(attributes)) return match
    const hash = createHash('sha256').update(body).digest('hex').slice(0, 16)
    const name = `${ASSET_DIR}/inline-${hash}.js`
    if (!written.has(name)) {
      written.add(name)
      pending.push(writeFile(join(clientPath, name), body, 'utf8'))
    }
    replaced += 1
    return `<script${attributes} src="/${name}"></script>`
  })
  await Promise.all(pending)
  await writeFile(file, output, 'utf8')
  const remaining = [...output.matchAll(INLINE_SCRIPT)].filter(([, attributes, body]) => executable(attributes) && !/(?:^|\s)src\s*=/i.test(attributes) && body.trim() !== '')
  if (remaining.length > 0) {
    console.error(`externalize-inline-scripts: ${relative(clientPath, file)} still has ${remaining.length} inline script(s)`)
    process.exit(1)
  }
}
console.log(`externalize-inline-scripts: ${replaced} inline script(s) in ${files.length} HTML file(s) -> ${written.size} file(s) in dist/client/${ASSET_DIR}`)
