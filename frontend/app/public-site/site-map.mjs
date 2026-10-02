// The public route set and its locale variants (docs/seo/I18N.md). Turkish is the default language at the root;
// English mirrors the same routes under /en. Shared by the route config, the prerender list, the meta tags and
// the post-build scripts so every list of public URLs comes from one place. Types: site-map.d.mts.

export const LOCALES = Object.freeze(['tr', 'en'])
export const DEFAULT_LOCALE = 'tr'

/** Locale-neutral paths of the content pages, in navigation order. */
export const STATIC_PAGES = Object.freeze(['/', '/courses', '/about', '/faq', '/privacy', '/security'])

/** Path of the prerendered not-found page (served by nginx for unknown public paths). */
export const NOT_FOUND_PAGE = '/404'

export function coursePath(slug) {
  return `/courses/${slug}`
}

/** "/courses" -> "/courses" (tr) or "/en/courses" (en); "/" -> "/" or "/en". */
export function localizedPath(locale, path) {
  if (locale === DEFAULT_LOCALE) return path
  return path === '/' ? `/${locale}` : `/${locale}${path}`
}

/** The locale of a public URL path: "/en" and "/en/..." are English, everything else Turkish. */
export function localeFromPath(pathname) {
  return pathname === '/en' || pathname.startsWith('/en/') ? 'en' : DEFAULT_LOCALE
}

/** Strips the locale prefix: "/en/courses" -> "/courses", "/en" -> "/". */
export function neutralPath(pathname) {
  const locale = localeFromPath(pathname)
  if (locale === DEFAULT_LOCALE) return pathname === '' ? '/' : pathname
  const rest = pathname.slice(locale.length + 1)
  return rest === '' ? '/' : rest
}

/** Every path to prerender: static pages and course pages in both locales, plus the not-found pages. */
export function prerenderPaths(slugs) {
  const neutral = [...STATIC_PAGES, ...slugs.map(coursePath), NOT_FOUND_PAGE]
  return LOCALES.flatMap(locale => neutral.map(path => localizedPath(locale, path)))
}

/** Absolute URL of a path on the public site (no trailing slash except for the root). */
export function absoluteUrl(siteUrl, path) {
  return path === '/' ? `${siteUrl}/` : `${siteUrl}${path}`
}
