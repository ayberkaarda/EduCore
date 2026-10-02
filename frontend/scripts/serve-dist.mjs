// `npm run serve:dist`: a static server for dist/client that mirrors the production nginx rules closely enough
// for Lighthouse CI and manual checks without Docker: same lookup order ($uri, $uri/index.html), /app/** -> SPA
// shell, unknown paths -> 404.html or en/404.html with status 404, gzip for text, the SPA security headers of
// docs/security/HEADERS.md (strict CSP: script-src 'self', style-src 'self') and the cache policy (hashed assets
// immutable, HTML no-cache). PORT (default 4173).
import { createReadStream, existsSync, statSync } from 'node:fs'
import { createServer } from 'node:http'
import { extname, join, normalize, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createGzip } from 'node:zlib'

const root = fileURLToPath(new URL('../dist/client/', import.meta.url))
const port = Number(process.env.PORT ?? '4173')

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.xml': 'application/xml; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.data': 'text/x-script',
}
const COMPRESSIBLE = new Set(['.html', '.js', '.css', '.json', '.xml', '.txt', '.svg', '.data'])

const SECURITY_HEADERS = {
  'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'",
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Permissions-Policy': 'accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()',
  'Cross-Origin-Opener-Policy': 'same-origin',
}

function file(relativePath) {
  const full = normalize(join(root, relativePath))
  if (!full.startsWith(root.endsWith(sep) ? root : root + sep)) return null
  return existsSync(full) && statSync(full).isFile() ? full : null
}

function resolve(pathname) {
  if (pathname === '/app' || pathname.startsWith('/app/')) {
    return { path: file(pathname.slice(1)) ?? file('__spa-fallback.html'), status: 200, noindex: true }
  }
  const direct = pathname === '/' ? file('index.html') : file(pathname.slice(1)) ?? file(join(pathname.slice(1), 'index.html'))
  if (direct) return { path: direct, status: 200 }
  const notFound = pathname === '/en' || pathname.startsWith('/en/') ? file(join('en', '404.html')) : file('404.html')
  return { path: notFound, status: 404 }
}

createServer((request, response) => {
  let pathname
  try {
    pathname = decodeURIComponent(new URL(request.url ?? '/', 'http://localhost').pathname)
  } catch {
    response.writeHead(400).end()
    return
  }
  const { path, status, noindex } = resolve(pathname)
  if (!path) {
    response.writeHead(404, SECURITY_HEADERS).end()
    return
  }
  const extension = extname(path)
  const headers = {
    ...SECURITY_HEADERS,
    'Content-Type': TYPES[extension] ?? 'application/octet-stream',
    'Cache-Control': pathname.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-cache',
    Vary: 'Accept-Encoding',
  }
  if (noindex) headers['X-Robots-Tag'] = 'noindex, nofollow'
  const gzip = COMPRESSIBLE.has(extension) && /\bgzip\b/.test(request.headers['accept-encoding'] ?? '')
  if (gzip) headers['Content-Encoding'] = 'gzip'
  response.writeHead(status, headers)
  if (request.method === 'HEAD') {
    response.end()
    return
  }
  const stream = createReadStream(path)
  if (gzip) stream.pipe(createGzip()).pipe(response)
  else stream.pipe(response)
}).listen(port, () => console.log(`serve-dist: dist/client on http://localhost:${port}`))
