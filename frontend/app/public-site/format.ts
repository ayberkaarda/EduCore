import type { Locale } from './site-map.mjs'

const DATE_FORMATS: Record<Locale, Intl.DateTimeFormat> = {
  // dd.MM.yyyy for tr-TR and d MMM yyyy for en-GB (docs/brand/BRAND_IDENTITY.md 11.3). UTC keeps the prerendered
  // output independent of the build machine's time zone.
  tr: new Intl.DateTimeFormat('tr-TR', { day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'UTC' }),
  en: new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }),
}

export function formatDate(locale: Locale, iso: string): string {
  return DATE_FORMATS[locale].format(new Date(iso))
}

/** The date part of an ISO instant (YYYY-MM-DD), for <time dateTime>. */
export function isoDate(iso: string): string {
  return new Date(iso).toISOString().slice(0, 10)
}
