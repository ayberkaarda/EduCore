import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { http, passthrough } from 'msw'
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { assertSameSite, fetchAllCourses, fetchCourse, fetchSiteFacts, PublicApiError } from '../app/public-site/public-api.mjs'
import { MOCK_COURSES, startMockPublicApi } from '../scripts/mock-public-api.mjs'
import { server as msw } from './msw'

// The build-time client against the contract-faithful mock (real HTTP on 127.0.0.1, MSW passes it through).
let mock: { server: Server; url: string }

beforeAll(async () => {
  mock = await startMockPublicApi({ port: 0, siteUrl: 'https://educore.example.org' })
})
afterAll(() => {
  mock.server.close()
})
beforeEach(() => {
  msw.use(http.all(/^http:\/\/127\.0\.0\.1:\d+\/.*/, () => passthrough()))
})

async function closedPort(): Promise<string> {
  const probe = createServer()
  await new Promise<void>(resolve => probe.listen(0, '127.0.0.1', resolve))
  const { port } = probe.address() as AddressInfo
  await new Promise(resolve => probe.close(resolve))
  return `http://127.0.0.1:${port}`
}

async function serve(handler: Parameters<typeof createServer>[1]): Promise<{ server: Server; url: string }> {
  const server = createServer(handler)
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  return { server, url: `http://127.0.0.1:${(server.address() as AddressInfo).port}` }
}

describe('public API client (build time)', () => {
  it('reads the site facts', async () => {
    const facts = await fetchSiteFacts(mock.url)
    expect(facts).toMatchObject({ name: 'EduCore', baseUrl: 'https://educore.example.org', languages: ['tr', 'en'] })
    expect(facts.dateModified).toBe('2026-09-30T16:05:00.000Z')
    expect(() => assertSameSite(facts, 'https://educore.example.org')).not.toThrow()
    expect(() => assertSameSite(facts, 'https://other.example.org')).toThrow(/EDUCORE_SEO_BASE_URL/)
  })

  it('reads every published course, sorted by name, with Turkish characters intact', async () => {
    const courses = await fetchAllCourses(mock.url)
    expect(courses).toHaveLength(MOCK_COURSES.length)
    expect(courses.map(course => course.name)).toContain('İşletim Sistemleri')
    expect(Object.keys(courses[0]).sort()).toEqual(['description', 'instructor', 'name', 'slug', 'term', 'updatedAt'])
  })

  it('follows every page of the catalog', async () => {
    const pages: { content: unknown[]; totalPages: number }[] = [
      { content: [MOCK_COURSES[0]], totalPages: 2 },
      { content: [MOCK_COURSES[1]], totalPages: 2 },
    ]
    const requested: string[] = []
    const paged = await serve((request, response) => {
      requested.push(request.url ?? '')
      const page = Number(new URL(request.url ?? '/', 'http://x').searchParams.get('page'))
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ ...pages[page], page, size: 100, totalElements: 2 }))
    })
    try {
      const courses = await fetchAllCourses(paged.url)
      expect(courses.map(course => course.slug)).toEqual([MOCK_COURSES[0].slug, MOCK_COURSES[1].slug])
      expect(requested).toEqual([
        '/api/v1/public/courses?page=0&size=100&sort=name&direction=asc',
        '/api/v1/public/courses?page=1&size=100&sort=name&direction=asc',
      ])
    } finally {
      paged.server.close()
    }
  })

  it('reads one course and fails on an unknown slug', async () => {
    await expect(fetchCourse(mock.url, 'dogrusal-cebir')).resolves.toMatchObject({ name: 'Doğrusal Cebir' })
    await expect(fetchCourse(mock.url, 'no-such-course')).rejects.toThrow(/no published course "no-such-course"/)
    await expect(fetchCourse(mock.url, '../admin')).rejects.toThrow(PublicApiError)
  })

  it('fails loudly when the API is unreachable', async () => {
    const url = await closedPort()
    await expect(fetchAllCourses(url)).rejects.toThrow(PublicApiError)
    await expect(fetchSiteFacts(url)).rejects.toThrow(/unreachable.*PUBLIC_API_URL/)
  })

  it('refuses an empty catalog instead of building an empty site', async () => {
    const empty = await serve((_request, response) => {
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }))
    })
    try {
      await expect(fetchAllCourses(empty.url)).rejects.toThrow(/no published courses/)
    } finally {
      empty.server.close()
    }
  })

  it('rejects malformed data (an error page, an invalid slug)', async () => {
    const broken = await serve((request, response) => {
      if ((request.url ?? '').includes('site-facts')) {
        response.writeHead(200, { 'Content-Type': 'text/html' })
        response.end('<html>proxy error</html>')
        return
      }
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ content: [{ ...MOCK_COURSES[0], slug: 'Bad Slug' }], page: 0, size: 100, totalElements: 1, totalPages: 1 }))
    })
    try {
      await expect(fetchSiteFacts(broken.url)).rejects.toThrow(/content type "text\/html"/)
      await expect(fetchAllCourses(broken.url)).rejects.toThrow(/not a valid slug/)
    } finally {
      broken.server.close()
    }
  })
})

describe('mock public API follows the caching contract', () => {
  it('sends a strong ETag, Cache-Control and X-Robots-Tag, and answers a matching If-None-Match with 304', async () => {
    const first = await fetch(`${mock.url}/api/v1/public/courses/dogrusal-cebir`)
    const etag = first.headers.get('etag') ?? ''
    expect(first.status).toBe(200)
    expect(etag).toMatch(/^"[0-9a-f]{64}"$/)
    expect(first.headers.get('cache-control')).toBe('max-age=300, public')
    expect(first.headers.get('x-robots-tag')).toBe('noindex, nofollow')
    const weak = await fetch(`${mock.url}/api/v1/public/courses/dogrusal-cebir`, { headers: { 'If-None-Match': `"other", W/${etag}` } })
    expect(weak.status).toBe(304)
    const head = await fetch(`${mock.url}/api/v1/public/site-facts`, { method: 'HEAD' })
    expect(head.status).toBe(200)
    expect(await head.text()).toBe('')
  })

  it('answers errors as problem details without caching headers and keeps /sitemap.xml indexable', async () => {
    const missing = await fetch(`${mock.url}/api/v1/public/courses/nope`)
    expect(missing.status).toBe(404)
    expect(missing.headers.get('etag')).toBeNull()
    expect(missing.headers.get('content-type')).toBe('application/problem+json')
    expect((await missing.json()).code).toBe('course/not-found')
    const badSort = await fetch(`${mock.url}/api/v1/public/courses?sort=id`)
    expect(badSort.status).toBe(400)
    const sitemap = await fetch(`${mock.url}/sitemap.xml`)
    expect(sitemap.headers.get('x-robots-tag')).toBeNull()
    expect(await sitemap.text()).toContain('<loc>https://educore.example.org/courses/dogrusal-cebir</loc>')
  })
})
