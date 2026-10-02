/** A published course as GET /api/v1/public/courses returns it (no id, account or enrollment data exists). */
export interface PublicCourse {
  readonly name: string
  readonly slug: string
  /** Empty string when the course has no term. */
  readonly term: string
  /** Empty string when the course has no instructor. */
  readonly instructor: string
  /** Empty string when the course has no description. */
  readonly description: string
  /** ISO-8601 instant (UTC). */
  readonly updatedAt: string
}

export interface SiteFacts {
  readonly name: string
  /** Origin of the public site without a trailing slash (EDUCORE_SEO_BASE_URL). */
  readonly baseUrl: string
  readonly description: string
  readonly languages: readonly string[]
  /** Catalog revision (ISO-8601 instant); null while nothing is published. */
  readonly dateModified: string | null
}

export declare class PublicApiError extends Error {
  constructor(message: string, options?: { cause?: unknown })
}

export declare function parseCourse(raw: unknown, where: string): PublicCourse
export declare function fetchSiteFacts(apiUrl: string): Promise<SiteFacts>
export declare function fetchAllCourses(apiUrl: string): Promise<readonly PublicCourse[]>
export declare function fetchCourse(apiUrl: string, slug: string): Promise<PublicCourse>
export declare function assertSameSite(siteFacts: SiteFacts, siteUrl: string): void
