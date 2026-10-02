export type PageKind = 'home' | 'courses' | 'course' | 'about' | 'faq' | 'privacy' | 'security'

export declare const ALLOWED_KEYS: ReadonlySet<string>
export declare const REQUIRED_TYPES: Readonly<Record<PageKind, readonly string[]>>

export interface JsonLdValidation {
  problems: string[]
  types: unknown[]
  data?: { '@context': string; '@graph': Record<string, unknown>[] }
}

export declare function validateJsonLdBlocks(rawBlocks: readonly string[], kind: string, where?: string): JsonLdValidation
export declare function pageKind(neutralPath: string): string
