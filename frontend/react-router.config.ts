import type { Config } from '@react-router/dev/config'
import { readBuildConfig } from './app/public-site/build-config.mjs'
import { assertSameSite, fetchAllCourses, fetchSiteFacts } from './app/public-site/public-api.mjs'
import { prerenderPaths } from './app/public-site/site-map.mjs'

// Decision D-03 (option A): no Node runtime in production. Public routes are prerendered to static HTML
// at build time; everything under /app is a client-rendered SPA served from __spa-fallback.html.
//
// The prerender list is fetched from the backend public API (PUBLIC_API_URL) at build time: every published
// course gets /courses/<slug> and /en/courses/<slug>. An unreachable API, an empty catalog, or a missing
// PUBLIC_API_URL / PUBLIC_SITE_URL fails the build with a message (docs/seo/BUILD.md); there is no fallback to
// an empty catalog. `npm run build:mock` runs the same build against scripts/mock-public-api.mjs.
export default {
  appDirectory: 'app',
  buildDirectory: 'dist',
  ssr: false,
  async prerender() {
    // `react-router dev` also asks for the list (to know which routes are prerendered): there the defaults of
    // readBuildConfig apply and an unreachable API only drops the course pages, with a warning.
    const production = process.env.NODE_ENV === 'production'
    try {
      const config = readBuildConfig(process.env, production ? 'production' : 'development')
      const facts = await fetchSiteFacts(config.apiUrl)
      if (production) assertSameSite(facts, config.siteUrl)
      const courses = await fetchAllCourses(config.apiUrl)
      const paths = prerenderPaths(courses.map(course => course.slug))
      console.log(`public-site: prerendering ${paths.length} paths (${courses.length} published courses) from ${config.apiUrl}`)
      return paths
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      if (!production) {
        console.warn(`public-site (development): ${message} Course pages are unavailable until the API answers.`)
        return prerenderPaths([])
      }
      console.error(`\npublic-site: BUILD FAILED. ${message}\n`)
      throw error
    }
  },
} satisfies Config
