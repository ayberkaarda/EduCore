export type Locale = 'tr' | 'en'

export declare const LOCALES: readonly Locale[]
export declare const DEFAULT_LOCALE: Locale
export declare const STATIC_PAGES: readonly string[]
export declare const NOT_FOUND_PAGE: string

export declare function coursePath(slug: string): string
export declare function localizedPath(locale: Locale, path: string): string
export declare function localeFromPath(pathname: string): Locale
export declare function neutralPath(pathname: string): string
export declare function prerenderPaths(slugs: readonly string[]): string[]
export declare function absoluteUrl(siteUrl: string, path: string): string
