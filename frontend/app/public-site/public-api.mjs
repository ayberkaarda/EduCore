// Client for the anonymous backend public API (docs/api/ROUTES.md, "Public API and sitemap"), used only at build
// time: by the prerender list, the route loaders and the post-build scripts. Every failure throws a
// PublicApiError with a message that names PUBLIC_API_URL and the route, so a build can never silently produce an
// empty catalog. Types: public-api.d.mts.

const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
const PAGE_SIZE = 100
const TIMEOUT_MS = 15_000
const MAX_PAGES = 1_000

export class PublicApiError extends Error {
  constructor(message, options) {
    super(message, options)
    this.name = 'PublicApiError'
  }
}

function describe(apiUrl, path) {
  return `${apiUrl}${path}`
}

async function getJson(apiUrl, path, { allowNotFound = false } = {}) {
  const url = describe(apiUrl, path)
  let response
  try {
    response = await fetch(url, {
      headers: { Accept: 'application/json' },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
  } catch (cause) {
    const reason = cause instanceof Error ? (cause.cause instanceof Error ? cause.cause.message : cause.message) : String(cause)
    throw new PublicApiError(
      `The public API is unreachable at ${url} (${reason}). Start the backend and check PUBLIC_API_URL, `
      + 'or run `npm run build:mock` for a local build against the bundled mock API.',
      { cause },
    )
  }
  if (allowNotFound && response.status === 404) return null
  if (!response.ok) {
    throw new PublicApiError(`The public API answered ${response.status} for ${url}; expected 200.`)
  }
  const type = response.headers.get('content-type') ?? ''
  if (!type.includes('json')) {
    throw new PublicApiError(`The public API answered ${url} with content type "${type}"; expected JSON.`)
  }
  try {
    return await response.json()
  } catch (cause) {
    throw new PublicApiError(`The public API answered ${url} with invalid JSON.`, { cause })
  }
}

function requireString(value, field, where, { optional = false } = {}) {
  if (value === null || value === undefined) {
    if (optional) return ''
    throw new PublicApiError(`${where}: field "${field}" is missing.`)
  }
  if (typeof value !== 'string') throw new PublicApiError(`${where}: field "${field}" is not a string.`)
  return value
}

function requireInstant(value, field, where, { optional = false } = {}) {
  if ((value === null || value === undefined) && optional) return null
  const text = requireString(value, field, where)
  if (Number.isNaN(Date.parse(text))) throw new PublicApiError(`${where}: field "${field}" is not an ISO-8601 instant.`)
  return new Date(text).toISOString()
}

/** Validates one PublicCourse (name, slug, term, instructor, description, updatedAt) and drops unknown fields. */
export function parseCourse(raw, where) {
  if (raw === null || typeof raw !== 'object') throw new PublicApiError(`${where}: a course is not an object.`)
  const slug = requireString(raw.slug, 'slug', where)
  if (!SLUG.test(slug) || slug.length > 80) throw new PublicApiError(`${where}: slug "${slug}" is not a valid slug.`)
  const name = requireString(raw.name, 'name', where).trim()
  if (name === '') throw new PublicApiError(`${where}: course "${slug}" has an empty name.`)
  return Object.freeze({
    name,
    slug,
    term: requireString(raw.term, 'term', where, { optional: true }).trim(),
    instructor: requireString(raw.instructor, 'instructor', where, { optional: true }).trim(),
    description: requireString(raw.description, 'description', where, { optional: true }).trim(),
    updatedAt: requireInstant(raw.updatedAt, 'updatedAt', where),
  })
}

/** GET /api/v1/public/site-facts */
export async function fetchSiteFacts(apiUrl) {
  const path = '/api/v1/public/site-facts'
  const where = describe(apiUrl, path)
  const raw = await getJson(apiUrl, path)
  if (raw === null || typeof raw !== 'object') throw new PublicApiError(`${where}: the body is not an object.`)
  const languages = Array.isArray(raw.languages) ? raw.languages.filter(item => typeof item === 'string') : []
  return Object.freeze({
    name: requireString(raw.name, 'name', where).trim(),
    baseUrl: requireString(raw.baseUrl, 'baseUrl', where).replace(/\/+$/, ''),
    description: requireString(raw.description, 'description', where).trim(),
    languages: Object.freeze(languages),
    dateModified: requireInstant(raw.dateModified, 'dateModified', where, { optional: true }),
  })
}

/**
 * GET /api/v1/public/courses, every page, sorted by name. Throws when the catalog is empty: a build must not
 * publish an empty catalog because the API had no data.
 */
export async function fetchAllCourses(apiUrl) {
  const courses = []
  for (let page = 0; page < MAX_PAGES; page += 1) {
    const path = `/api/v1/public/courses?page=${page}&size=${PAGE_SIZE}&sort=name&direction=asc`
    const where = describe(apiUrl, path)
    const body = await getJson(apiUrl, path)
    if (body === null || typeof body !== 'object' || !Array.isArray(body.content)) {
      throw new PublicApiError(`${where}: the body is not a page ({content, page, size, totalElements, totalPages}).`)
    }
    for (const item of body.content) courses.push(parseCourse(item, where))
    const totalPages = Number(body.totalPages)
    if (!Number.isInteger(totalPages) || page + 1 >= totalPages || body.content.length === 0) break
  }
  if (courses.length === 0) {
    throw new PublicApiError(
      `The public API at ${apiUrl} returned no published courses. Publish at least one course before building `
      + 'the public site; an empty catalog is never prerendered.',
    )
  }
  const seen = new Set()
  for (const course of courses) {
    if (seen.has(course.slug)) throw new PublicApiError(`The public API returned slug "${course.slug}" twice.`)
    seen.add(course.slug)
  }
  return Object.freeze(courses)
}

/** GET /api/v1/public/courses/{slug}; throws on 404 because every prerendered slug must exist. */
export async function fetchCourse(apiUrl, slug) {
  if (!SLUG.test(slug)) throw new PublicApiError(`"${slug}" is not a valid course slug.`)
  const path = `/api/v1/public/courses/${slug}`
  const raw = await getJson(apiUrl, path, { allowNotFound: true })
  if (raw === null) throw new PublicApiError(`The public API has no published course "${slug}" (404 for ${describe(apiUrl, path)}).`)
  return parseCourse(raw, describe(apiUrl, path))
}

/** The site facts' baseUrl must equal PUBLIC_SITE_URL, otherwise canonical URLs and the backend sitemap disagree. */
export function assertSameSite(siteFacts, siteUrl) {
  if (siteFacts.baseUrl !== siteUrl) {
    throw new PublicApiError(
      `PUBLIC_SITE_URL (${siteUrl}) differs from the backend's EDUCORE_SEO_BASE_URL (${siteFacts.baseUrl}, from `
      + '/api/v1/public/site-facts). Set both to the same origin.',
    )
  }
}
