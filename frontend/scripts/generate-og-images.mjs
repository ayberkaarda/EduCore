// Renders the static Open Graph images (1200x630 PNG, one per page template and language) and the 512x512
// organisation logo into public/og/ and public/brand/ with headless Chrome. The PNGs are committed; run this script
// again only when the brand or the template headings change:
//   CHROME_PATH="C:\Program Files\Google\Chrome\Application\chrome.exe" node scripts/generate-og-images.mjs
// Design: docs/brand/BRAND_IDENTITY.md (petrol 900 surface, paper text, the Ledger Mark, IBM Plex Sans 600).
import { execFile } from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { promisify } from 'node:util'

const run = promisify(execFile)
const chrome = process.env.CHROME_PATH
if (!chrome) {
  console.error('generate-og-images: set CHROME_PATH to a Chrome or Chromium executable.')
  process.exit(1)
}

const publicDir = fileURLToPath(new URL('../public/', import.meta.url))
const fontDir = fileURLToPath(new URL('../node_modules/@fontsource/ibm-plex-sans/files/', import.meta.url))

const TEMPLATES = {
  home: { tr: 'Ders ve öğrenci yönetim platformu', en: 'Course and student management platform' },
  courses: { tr: 'Ders kataloğu', en: 'Course catalog' },
  course: { tr: 'Yayımlanan ders', en: 'Published course' },
  page: { tr: 'Güvenebileceğiniz kayıtlar', en: 'Records you can trust' },
}
const FOOTER = { tr: 'Ders kataloğu · Derse kayıt · CSV içe aktarımı · Rol tabanlı erişim', en: 'Course catalog · Enrollment · CSV import · Role-based access' }

const GLYPH = '<g fill="#F2F4F5"><rect x="13" y="13.5" width="22" height="5" rx="1.5"/><rect x="13" y="21.5" width="13" height="5" rx="1.5"/><rect x="30" y="21.5" width="5" height="5" rx="1.5"/><rect x="13" y="29.5" width="22" height="5" rx="1.5"/></g>'
// On the petrol surface the coloured tile does not read (brand 4.4), so the OG images use the paper glyph alone;
// the square logo is the tile mark.
const MARK = `<svg viewBox="8 8 32 32" width="96" height="96" aria-hidden="true">${GLYPH}</svg>`
const TILE = `<svg viewBox="0 0 48 48" aria-hidden="true"><rect width="48" height="48" rx="12" fill="#135263"/>${GLYPH}</svg>`

function fontFace(weight) {
  const faces = ['latin', 'latin-ext'].map(subset => {
    const url = pathToFileURL(join(fontDir, `ibm-plex-sans-${subset}-${weight}-normal.woff2`)).href
    return `@font-face{font-family:"IBM Plex Sans";font-weight:${weight};src:url("${url}") format("woff2")}`
  })
  return faces.join('')
}

function ogHtml(heading, footer) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
${fontFace(400)}${fontFace(600)}
html,body{margin:0;width:1200px;height:630px;overflow:hidden}
body{background:#0E2A34;color:#F2F4F5;font-family:"IBM Plex Sans",sans-serif;display:flex;flex-direction:column;justify-content:space-between;padding:72px 80px;box-sizing:border-box}
.brand{display:flex;align-items:center;gap:28px;font-size:56px;font-weight:600;letter-spacing:-0.01em}
h1{margin:0;font-size:68px;line-height:1.12;font-weight:600;letter-spacing:-0.02em;max-width:980px}
.rule{height:2px;background:#1D4D5D;margin:0 0 28px}
p{margin:0;font-size:28px;color:#D6E3E8}
</style></head><body><div class="brand">${MARK}<span>EduCore</span></div><h1>${heading}</h1><div><div class="rule"></div><p>${footer}</p></div></body></html>`
}

function logoHtml() {
  return `<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;width:512px;height:512px;overflow:hidden;background:#F2F4F5}svg{display:block;width:512px;height:512px}</style></head><body>${TILE}</body></html>`
}

async function screenshot(html, width, height, output, work) {
  const page = join(work, 'page.html')
  await writeFile(page, html, 'utf8')
  await run(chrome, [
    '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
    '--default-background-color=00000000', `--window-size=${width},${height}`, `--screenshot=${output}`,
    '--virtual-time-budget=3000', pathToFileURL(page).href,
  ], { timeout: 60_000 })
  const png = await readFile(output)
  const actual = [png.readUInt32BE(16), png.readUInt32BE(20)]
  if (actual[0] !== width || actual[1] !== height) throw new Error(`${output} is ${actual.join('x')}, expected ${width}x${height}`)
  return png.length
}

const work = await mkdtemp(join(tmpdir(), 'educore-og-'))
try {
  for (const [template, headings] of Object.entries(TEMPLATES)) {
    for (const locale of ['tr', 'en']) {
      const output = join(publicDir, 'og', `${template}-${locale}.png`)
      const bytes = await screenshot(ogHtml(headings[locale], FOOTER[locale]), 1200, 630, output, work)
      console.log(`og/${template}-${locale}.png 1200x630 ${bytes} B`)
    }
  }
  const logo = join(publicDir, 'brand', 'logo-512.png')
  console.log(`brand/logo-512.png 512x512 ${await screenshot(logoHtml(), 512, 512, logo, work)} B`)
} finally {
  await rm(work, { recursive: true, force: true })
}
