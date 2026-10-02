// Build-time configuration of the public site, shared by react-router.config.ts (prerender list), the route
// loaders (prerendered HTML) and the post-build scripts (robots.txt, llms.txt, sitemap, checks).
// Plain JavaScript so the Node scripts can import it without a TypeScript step; types: build-config.d.mts.
//
// Environment (see docs/seo/BUILD.md):
//   PUBLIC_API_URL    origin of the EduCore backend that serves /api/v1/public/** (required for a build)
//   PUBLIC_SITE_URL   absolute origin of the public site; the same value as the backend's EDUCORE_SEO_BASE_URL
//                     (required for a build: canonical URLs, hreflang, sitemap, robots.txt, llms.txt)
//   EDUCORE_SEO_AI_CRAWLERS_ALLOW_PUBLIC  "true" (default) or "false": may the listed AI crawlers read public paths
//   EDUCORE_SEO_AI_CRAWLERS_USER_AGENTS   comma-separated user agents (default mirrors educore.seo.ai-crawlers)
//   PUBLIC_COURSE_MODE  Onsite (default), Online or Blended: CourseInstance.courseMode of every course in JSON-LD
//                       (the public API has no per-course teaching mode)

export const DEFAULT_AI_CRAWLERS = Object.freeze([
  'GPTBot', 'ClaudeBot', 'Claude-SearchBot', 'PerplexityBot', 'Google-Extended', 'CCBot',
])

/** schema.org CourseInstance.courseMode values accepted for PUBLIC_COURSE_MODE. */
export const COURSE_MODES = Object.freeze(['Onsite', 'Online', 'Blended'])

export class BuildConfigError extends Error {
  constructor(message) {
    super(message)
    this.name = 'BuildConfigError'
  }
}

/**
 * Validates an origin the same way the backend validates educore.seo.base-url: absolute http(s), a host, no user
 * info, path (other than "/"), query or fragment. Returns the origin without a trailing slash.
 */
export function parseOrigin(name, value) {
  const raw = (value ?? '').trim()
  if (raw === '') throw new BuildConfigError(`${name} is not set.`)
  let url
  try {
    url = new URL(raw)
  } catch {
    throw new BuildConfigError(`${name} must be an absolute http(s) origin such as https://educore.example.org.`)
  }
  const pathOk = url.pathname === '' || url.pathname === '/'
  const valid = (url.protocol === 'http:' || url.protocol === 'https:') && url.hostname !== ''
    && url.username === '' && url.password === '' && url.search === '' && url.hash === '' && pathOk
    && !raw.includes('?') && !raw.includes('#')
  if (!valid) {
    throw new BuildConfigError(
      `${name} must be an absolute http(s) origin without user info, path, query or fragment (got a value with one of them).`,
    )
  }
  return url.origin
}

function parseBoolean(name, value, fallback) {
  if (value === undefined || value.trim() === '') return fallback
  const normalized = value.trim().toLowerCase()
  if (normalized === 'true') return true
  if (normalized === 'false') return false
  throw new BuildConfigError(`${name} must be "true" or "false".`)
}

function parseUserAgents(value) {
  if (value === undefined || value.trim() === '') return [...DEFAULT_AI_CRAWLERS]
  const agents = value.split(',').map(agent => agent.trim()).filter(agent => agent !== '')
  for (const agent of agents) {
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(agent)) {
      throw new BuildConfigError('EDUCORE_SEO_AI_CRAWLERS_USER_AGENTS may only contain user-agent tokens (letters, digits, ".", "_", "-").')
    }
  }
  return [...new Set(agents)]
}

/**
 * Reads the configuration. In `production` mode (every `react-router build`) PUBLIC_API_URL and PUBLIC_SITE_URL
 * are required and the function throws a BuildConfigError naming the missing variable. In `development` mode
 * (`react-router dev`) they default to the local backend and dev server.
 */
export function readBuildConfig(env, mode = 'production') {
  const production = mode === 'production'
  const apiValue = env.PUBLIC_API_URL ?? (production ? undefined : 'http://localhost:8080')
  const siteValue = env.PUBLIC_SITE_URL ?? (production ? undefined : 'http://localhost:3000')
  if (production && (apiValue === undefined || apiValue.trim() === '')) {
    throw new BuildConfigError(
      'PUBLIC_API_URL is not set. The public pages are prerendered from the backend public API at build time: '
      + 'start the backend and set PUBLIC_API_URL to its origin (for example http://localhost:8080), '
      + 'or run `npm run build:mock` for a local build against the bundled mock API.',
    )
  }
  if (production && (siteValue === undefined || siteValue.trim() === '')) {
    throw new BuildConfigError(
      'PUBLIC_SITE_URL is not set. It must be the absolute origin of the public site, the same value as the '
      + "backend's EDUCORE_SEO_BASE_URL (canonical URLs, hreflang, sitemap and robots.txt are built from it).",
    )
  }
  const courseMode = (env.PUBLIC_COURSE_MODE ?? '').trim() || 'Onsite'
  if (!COURSE_MODES.includes(courseMode)) {
    throw new BuildConfigError(`PUBLIC_COURSE_MODE must be one of ${COURSE_MODES.join(', ')}.`)
  }
  return Object.freeze({
    apiUrl: parseOrigin('PUBLIC_API_URL', apiValue),
    siteUrl: parseOrigin('PUBLIC_SITE_URL', siteValue),
    courseMode,
    aiCrawlers: Object.freeze({
      allowPublic: parseBoolean('EDUCORE_SEO_AI_CRAWLERS_ALLOW_PUBLIC', env.EDUCORE_SEO_AI_CRAWLERS_ALLOW_PUBLIC, true),
      userAgents: Object.freeze(parseUserAgents(env.EDUCORE_SEO_AI_CRAWLERS_USER_AGENTS)),
    }),
  })
}
