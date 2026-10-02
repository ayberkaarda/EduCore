import type { LinksFunction } from 'react-router'
import { Outlet, useLocation, useMatches } from 'react-router'
import sansLatin400 from '@fontsource/ibm-plex-sans/files/ibm-plex-sans-latin-400-normal.woff2?url'
import sansLatin600 from '@fontsource/ibm-plex-sans/files/ibm-plex-sans-latin-600-normal.woff2?url'
import publicCss from '../../public-site/styles/public.css?url'
import { copyFor } from '../../public-site/copy'
import { LOCALES, localeFromPath, localizedPath, neutralPath, type Locale } from '../../public-site/site-map.mjs'

// Layout of every public page (Turkish at the root, English under /en). The pages are prerendered and served
// without the application bundle (scripts/postbuild-public.mjs), so navigation uses plain links and the layout
// renders no interactive React state. `handle.publicSite` tells root.tsx to render the page without the /app
// providers and to set <html lang> from the URL.

export const handle = { publicSite: true }

export const links: LinksFunction = () => [
  { rel: 'preload', href: publicCss, as: 'style' },
  { rel: 'stylesheet', href: publicCss },
  // The two faces the first screen uses (body and headings); latin-ext and the other weights load on demand
  // through unicode-range, with font-display: swap.
  { rel: 'preload', href: sansLatin400, as: 'font', type: 'font/woff2', crossOrigin: 'anonymous' },
  { rel: 'preload', href: sansLatin600, as: 'font', type: 'font/woff2', crossOrigin: 'anonymous' },
]

const LOCALE_LABEL: Record<Locale, string> = { tr: 'Türkçe', en: 'English' }

function LanguageSwitcher({ locale, targetPath }: { locale: Locale; targetPath: string }) {
  const t = copyFor(locale)
  return (
    <nav className="pub-lang" aria-label={t.ui.languageSwitcher}>
      <ul>
        {LOCALES.map(option => (
          <li key={option}>
            <a
              href={localizedPath(option, targetPath)}
              hrefLang={option}
              lang={option}
              aria-current={option === locale ? 'true' : undefined}
            >
              {LOCALE_LABEL[option]}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  )
}

export default function PublicLayout() {
  const { pathname } = useLocation()
  const matches = useMatches()
  const locale = localeFromPath(pathname)
  const t = copyFor(locale)
  const notFound = matches.some(match => (match.handle as { notFound?: boolean } | undefined)?.notFound)
  // The language switcher keeps the visitor on the same page; on the not-found page it leads to the other home.
  const targetPath = notFound ? '/' : neutralPath(pathname)
  const current = neutralPath(pathname)
  const nav = [
    { path: '/courses', label: t.ui.nav.courses },
    { path: '/about', label: t.ui.nav.about },
    { path: '/faq', label: t.ui.nav.faq },
  ]
  return (
    <div className="pub">
      <a className="pub-skip" href="#main">{t.ui.skipToContent}</a>
      <header className="pub-header">
        <div className="pub-header-inner">
          <a className="pub-brand" href={localizedPath(locale, '/')} aria-label={`EduCore, ${t.ui.nav.home}`}>
            <picture>
              <source srcSet="/brand/logo-full-dark.svg" media="(prefers-color-scheme: dark)" />
              <img src="/brand/logo-full.svg" width="150" height="28" alt="" />
            </picture>
          </a>
          <nav className="pub-nav" aria-label={t.ui.primaryNav}>
            <ul>
              {nav.map(item => {
                const active = current === item.path || current.startsWith(`${item.path}/`)
                return (
                  <li key={item.path}>
                    <a href={localizedPath(locale, item.path)} aria-current={active ? 'page' : undefined}>{item.label}</a>
                  </li>
                )
              })}
            </ul>
          </nav>
          <div className="pub-header-tools">
            <LanguageSwitcher locale={locale} targetPath={targetPath} />
            <a className="pub-signin" href="/app/login" rel="nofollow">{t.ui.signIn}</a>
          </div>
        </div>
      </header>
      <Outlet />
      <footer className="pub-footer">
        <div className="pub-footer-inner">
          <p>{t.ui.footerLine}</p>
          <nav aria-label={t.ui.footerNav}>
            <ul>
              <li><a href={localizedPath(locale, '/courses')}>{t.ui.nav.courses}</a></li>
              <li><a href={localizedPath(locale, '/about')}>{t.ui.nav.about}</a></li>
              <li><a href={localizedPath(locale, '/faq')}>{t.ui.nav.faq}</a></li>
              <li><a href={localizedPath(locale, '/privacy')}>{t.ui.nav.privacy}</a></li>
              <li><a href={localizedPath(locale, '/security')}>{t.ui.nav.security}</a></li>
            </ul>
          </nav>
        </div>
      </footer>
    </div>
  )
}
