// Contract-faithful mock of the backend public API (docs/api/ROUTES.md, "Public API and sitemap"), for local
// builds and tests when the real backend is not running. Synthetic data only.
//
//   MOCK_PUBLIC_API_PORT  port to listen on (default 8787; 0 picks a free port)
//   MOCK_SITE_URL         baseUrl reported by /site-facts and used in the sitemap (default http://localhost:4173)
//
// Served: GET/HEAD /api/v1/public/courses, /api/v1/public/courses/{slug}, /api/v1/public/site-facts and
// /sitemap.xml, with the contract's strong SHA-256 ETag, `Cache-Control: max-age=300, public`, 304 on a matching
// If-None-Match (weak comparison, lists and `*`), Problem Details errors without caching headers and
// `X-Robots-Tag: noindex, nofollow` on every /api/** response. Prints "mock-public-api listening on <url>".
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import { fileURLToPath } from 'node:url'

export const MOCK_COURSES = Object.freeze([
  {
    name: 'Veri Yapıları ve Algoritmalar',
    slug: 'veri-yapilari-ve-algoritmalar',
    term: '2026 Güz',
    instructor: 'Dr. Elif Şahin',
    description: 'Diziler, bağlı listeler, yığınlar, kuyruklar, ağaçlar ve çizgeler; sıralama ve arama algoritmalarının zaman ve bellek karmaşıklığı üzerinden karşılaştırılması.',
    updatedAt: '2026-09-28T08:15:00Z',
  },
  {
    name: 'Doğrusal Cebir',
    slug: 'dogrusal-cebir',
    term: '2026 Güz',
    instructor: 'Doç. Dr. Mert Öztürk',
    description: 'Vektör uzayları, matrisler, determinantlar, özdeğerler ve doğrusal dönüşümler; mühendislik problemlerinde matris hesaplarının kullanımı.',
    updatedAt: '2026-09-21T10:00:00Z',
  },
  {
    name: 'İşletim Sistemleri',
    slug: 'isletim-sistemleri',
    term: '2026 Güz',
    instructor: 'Dr. Can Yılmaz',
    description: 'Süreçler ve iş parçacıkları, zamanlama, eşzamanlılık, bellek yönetimi ve dosya sistemleri; küçük bir çekirdek modülü üzerinde uygulamalı çalışma.',
    updatedAt: '2026-09-25T13:40:00Z',
  },
  {
    name: 'Türk Dili I',
    slug: 'turk-dili-i',
    term: '2026 Güz',
    instructor: 'Öğr. Gör. Zeynep Çelik',
    description: 'Yazım kuralları, noktalama, paragraf kurma ve akademik metin yazımı; ğ, ı, İ, ş gibi harflerin doğru kullanımı dahil.',
    updatedAt: '2026-09-18T07:30:00Z',
  },
  {
    name: 'Web Güvenliği',
    slug: 'web-guvenligi',
    term: '2027 Bahar',
    instructor: 'Dr. Burak Aydın',
    description: 'XSS, CSRF ve içerik güvenlik politikası; </script><script>alert(1)</script> gibi bir girdinin HTML ve JSON içinde neden kaçışlanması gerektiği örneklerle incelenir.',
    updatedAt: '2026-09-30T16:05:00Z',
  },
  {
    name: 'Sayısal Yöntemler',
    slug: 'sayisal-yontemler',
    term: '2027 Bahar',
    instructor: 'Prof. Dr. Ayşe Kaya',
    description: '',
    updatedAt: '2026-09-12T09:00:00Z',
  },
  {
    name: 'Database Systems',
    slug: 'database-systems',
    term: '2027 Spring',
    instructor: 'Dr. Deniz Arslan',
    description: 'Relational modelling, SQL, indexing, transactions and isolation levels, taught in English with PostgreSQL lab sessions.',
    updatedAt: '2026-09-29T11:20:00Z',
  },
  {
    name: 'Bilgisayar Mühendisliğinde Proje Yönetimi, Yazılım Kalitesi ve Mesleki Etik Semineri',
    slug: 'bilgisayar-muhendisliginde-proje-yonetimi',
    term: '2027 Bahar',
    instructor: 'Dr. Selin Koç',
    description: 'Gereksinim analizi, sürüm planlama, kod incelemesi, test stratejileri ve mühendislik etiği üzerine haftalık seminer.',
    updatedAt: '2026-09-27T14:10:00Z',
  },
].map(course => Object.freeze(course)))

const SORT_KEYS = new Set(['name', 'term', 'updatedAt'])
const STATIC_ROUTES = ['/', '/courses', '/about', '/faq', '/privacy', '/security']

function etagOf(body) {
  return `"${createHash('sha256').update(body).digest('hex')}"`
}

function matches(ifNoneMatch, etag) {
  if (!ifNoneMatch) return false
  return ifNoneMatch.split(',').map(value => value.trim()).some(value =>
    value === '*' || value.replace(/^W\//, '') === etag)
}

function catalogRevision() {
  return MOCK_COURSES.map(course => course.updatedAt).sort().at(-1) ?? null
}

function problem(response, status, code, title, method) {
  const body = JSON.stringify({ type: `/problems/${code}`, title, status, code })
  response.writeHead(status, {
    'Content-Type': 'application/problem+json',
    'Cache-Control': 'no-cache, no-store, max-age=0, must-revalidate',
    'X-Robots-Tag': 'noindex, nofollow',
    'Content-Length': Buffer.byteLength(body),
  })
  response.end(method === 'HEAD' ? undefined : body)
}

function cached(request, response, body, contentType, api) {
  const etag = etagOf(body)
  const headers = { 'Cache-Control': 'max-age=300, public', ETag: etag }
  if (api) headers['X-Robots-Tag'] = 'noindex, nofollow'
  if (matches(request.headers['if-none-match'], etag)) {
    response.writeHead(304, headers)
    response.end()
    return
  }
  response.writeHead(200, { ...headers, 'Content-Type': contentType, 'Content-Length': Buffer.byteLength(body) })
  response.end(request.method === 'HEAD' ? undefined : body)
}

function compare(a, b, key) {
  return (a[key] ?? '').localeCompare(b[key] ?? '', 'tr') || a.slug.localeCompare(b.slug)
}

function listCourses(url) {
  const page = Number(url.searchParams.get('page') ?? '0')
  const size = Number(url.searchParams.get('size') ?? '20')
  const sort = url.searchParams.get('sort') ?? 'name'
  const direction = url.searchParams.get('direction') ?? 'asc'
  if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > 100) return { error: ['request/invalid', 'Invalid paging parameters.'] }
  if (!SORT_KEYS.has(sort) || !['asc', 'desc'].includes(direction)) return { error: ['sort/invalid', 'Unsupported sort.'] }
  const sorted = [...MOCK_COURSES].sort((a, b) => compare(a, b, sort) * (direction === 'desc' ? -1 : 1))
  const totalPages = Math.ceil(sorted.length / size)
  return {
    body: { content: sorted.slice(page * size, page * size + size), page, size, totalElements: sorted.length, totalPages },
  }
}

function sitemap(siteUrl) {
  const revision = catalogRevision()
  const escape = text => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const entries = STATIC_ROUTES.map(path => {
    const lastmod = path === '/' || path === '/courses' ? `<lastmod>${revision.replace('.000Z', 'Z')}</lastmod>` : ''
    return `<url><loc>${escape(siteUrl + path)}</loc>${lastmod}</url>`
  })
  for (const course of [...MOCK_COURSES].sort((a, b) => compare(a, b, 'name'))) {
    entries.push(`<url><loc>${escape(`${siteUrl}/courses/${course.slug}`)}</loc><lastmod>${course.updatedAt}</lastmod></url>`)
  }
  return `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${entries.join('')}</urlset>`
}

export function createMockPublicApi({ siteUrl = 'http://localhost:4173' } = {}) {
  return createServer((request, response) => {
    const url = new URL(request.url ?? '/', 'http://mock.invalid')
    const method = request.method ?? 'GET'
    const api = url.pathname.startsWith('/api/')
    if (method !== 'GET' && method !== 'HEAD') {
      if (api) return problem(response, 401, 'auth/unauthenticated', 'Authentication is required.', method)
      response.writeHead(405).end()
      return
    }
    if (url.pathname === '/api/v1/public/site-facts') {
      return cached(request, response, JSON.stringify({
        name: 'EduCore',
        baseUrl: siteUrl,
        description: 'EduCore is a course and student management platform: a public course catalog, self-service enrollment for students and administration tools for staff.',
        languages: ['tr', 'en'],
        dateModified: catalogRevision(),
      }), 'application/json', true)
    }
    if (url.pathname === '/api/v1/public/courses') {
      const result = listCourses(url)
      if (result.error) return problem(response, 400, result.error[0], result.error[1], method)
      return cached(request, response, JSON.stringify(result.body), 'application/json', true)
    }
    const detail = /^\/api\/v1\/public\/courses\/([^/]+)$/.exec(url.pathname)
    if (detail) {
      const course = MOCK_COURSES.find(item => item.slug === detail[1])
      if (!course) return problem(response, 404, 'course/not-found', 'Course not found.', method)
      return cached(request, response, JSON.stringify(course), 'application/json', true)
    }
    if (url.pathname === '/sitemap.xml') {
      return cached(request, response, sitemap(siteUrl), 'application/xml', false)
    }
    if (api) return problem(response, 401, 'auth/unauthenticated', 'Authentication is required.', method)
    problem(response, 404, 'request/not-found', 'Not found.', method)
  })
}

/** Starts the mock and resolves with its origin once it listens. */
export function startMockPublicApi({ port = 8787, siteUrl } = {}) {
  const server = createMockPublicApi({ siteUrl })
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, '127.0.0.1', () => {
      const address = server.address()
      resolve({ server, url: `http://127.0.0.1:${address.port}` })
    })
  })
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const port = Number(process.env.MOCK_PUBLIC_API_PORT ?? '8787')
  const { url } = await startMockPublicApi({ port, siteUrl: process.env.MOCK_SITE_URL ?? 'http://localhost:4173' })
  console.log(`mock-public-api listening on ${url}`)
}
