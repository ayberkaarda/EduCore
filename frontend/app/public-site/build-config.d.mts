export declare const DEFAULT_AI_CRAWLERS: readonly string[]

export type CourseMode = 'Onsite' | 'Online' | 'Blended'
export declare const COURSE_MODES: readonly CourseMode[]

export declare class BuildConfigError extends Error {
  constructor(message: string)
}

export interface AiCrawlerPolicy {
  readonly allowPublic: boolean
  readonly userAgents: readonly string[]
}

export interface BuildConfig {
  /** Origin of the backend serving /api/v1/public/**, without a trailing slash. */
  readonly apiUrl: string
  /** Origin of the public site (EDUCORE_SEO_BASE_URL), without a trailing slash. */
  readonly siteUrl: string
  /** CourseInstance.courseMode used for every course (PUBLIC_COURSE_MODE). */
  readonly courseMode: CourseMode
  readonly aiCrawlers: AiCrawlerPolicy
}

export declare function parseOrigin(name: string, value: string | undefined): string

export declare function readBuildConfig(
  env: Record<string, string | undefined>,
  mode?: 'production' | 'development',
): BuildConfig
