import type { MetaDescriptor } from 'react-router'
import { absoluteUrl, LOCALES, localizedPath, DEFAULT_LOCALE, type Locale } from '../public-site/site-map.mjs'

// Meta tags of the public pages (docs/seo/META.md): title, description, canonical, hreflang alternates for every
// locale plus x-default, robots, Open Graph and Twitter cards. URLs are absolute, built from PUBLIC_SITE_URL
// (the backend's EDUCORE_SEO_BASE_URL), which the route loaders pass in.

export type OgTemplate = 'home' | 'courses' | 'course' | 'page'

export const OG_IMAGE_WIDTH = 1200
export const OG_IMAGE_HEIGHT = 630

const OG_LOCALE: Record<Locale, string> = { tr: 'tr_TR', en: 'en_GB' }
const OG_ALT: Record<Locale, string> = {
  tr: 'EduCore logosu ve sayfa başlığı',
  en: 'EduCore logo and page heading',
}

export interface PageMetaInput {
  siteUrl: string
  locale: Locale
  /** Locale-neutral path, e.g. "/courses" or "/courses/linear-algebra". */
  path: string
  title: string
  description: string
  ogTemplate: OgTemplate
  ogType?: 'website' | 'article'
  /** Only the 404 page is excluded from indexing. */
  noindex?: boolean
  /** The page's JSON-LD descriptor (jsonLdMeta), if any. */
  jsonLd?: MetaDescriptor
}

export function ogImagePath(template: OgTemplate, locale: Locale): string {
  return `/og/${template}-${locale}.png`
}

export function alternateLinks(siteUrl: string, path: string): MetaDescriptor[] {
  const links: MetaDescriptor[] = LOCALES.map(locale => ({
    tagName: 'link',
    rel: 'alternate',
    hrefLang: locale,
    href: absoluteUrl(siteUrl, localizedPath(locale, path)),
  }))
  links.push({ tagName: 'link', rel: 'alternate', hrefLang: 'x-default', href: absoluteUrl(siteUrl, localizedPath(DEFAULT_LOCALE, path)) })
  return links
}

export function pageMeta(input: PageMetaInput): MetaDescriptor[] {
  const { siteUrl, locale, path, title, description } = input
  const url = absoluteUrl(siteUrl, localizedPath(locale, path))
  const image = `${siteUrl}${ogImagePath(input.ogTemplate, locale)}`
  const descriptors: MetaDescriptor[] = [
    { title },
    { name: 'description', content: description },
    { name: 'robots', content: input.noindex ? 'noindex, follow' : 'index, follow' },
  ]
  if (!input.noindex) {
    descriptors.push({ tagName: 'link', rel: 'canonical', href: url }, ...alternateLinks(siteUrl, path))
  }
  descriptors.push(
    { property: 'og:type', content: input.ogType ?? 'website' },
    { property: 'og:site_name', content: 'EduCore' },
    { property: 'og:title', content: title },
    { property: 'og:description', content: description },
    { property: 'og:url', content: url },
    { property: 'og:locale', content: OG_LOCALE[locale] },
    ...LOCALES.filter(other => other !== locale).map(other => ({ property: 'og:locale:alternate', content: OG_LOCALE[other] })),
    { property: 'og:image', content: image },
    { property: 'og:image:type', content: 'image/png' },
    { property: 'og:image:width', content: String(OG_IMAGE_WIDTH) },
    { property: 'og:image:height', content: String(OG_IMAGE_HEIGHT) },
    { property: 'og:image:alt', content: OG_ALT[locale] },
    { name: 'twitter:card', content: 'summary_large_image' },
    { name: 'twitter:title', content: title },
    { name: 'twitter:description', content: description },
    { name: 'twitter:image', content: image },
    { name: 'twitter:image:alt', content: OG_ALT[locale] },
  )
  if (input.jsonLd) descriptors.push(input.jsonLd)
  return descriptors
}
