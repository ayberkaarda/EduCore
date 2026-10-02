import { readBuildConfig, type BuildConfig } from './build-config.mjs'
import { assertSameSite, fetchAllCourses, fetchCourse, fetchSiteFacts, type PublicCourse, type SiteFacts } from './public-api.mjs'

// Build-time data access for the public route loaders. Loaders run only while React Router prerenders the
// public pages (ssr: false), so this module never reaches the browser. Any failure throws and fails the build.

export interface SiteData {
  config: BuildConfig
  facts: SiteFacts
  courses: readonly PublicCourse[]
}

const production = import.meta.env.PROD
let cached: Promise<SiteData> | undefined

async function load(): Promise<SiteData> {
  const config = readBuildConfig(process.env, production ? 'production' : 'development')
  const facts = await fetchSiteFacts(config.apiUrl)
  if (production) assertSameSite(facts, config.siteUrl)
  const courses = await fetchAllCourses(config.apiUrl)
  return { config, facts, courses }
}

/** Site facts and the whole catalog, fetched once per build (every request in development). */
export function siteData(): Promise<SiteData> {
  if (!production) return load()
  cached ??= load()
  return cached
}

/** One published course by slug from GET /api/v1/public/courses/{slug}. */
export async function courseData(slug: string): Promise<{ config: BuildConfig; facts: SiteFacts; course: PublicCourse }> {
  const { config, facts } = await siteData()
  return { config, facts, course: await fetchCourse(config.apiUrl, slug) }
}
