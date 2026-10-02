import { useContext } from 'react'
import { UNSAFE_FrameworkContext, useLocation, useMatches } from 'react-router'
import { localeFromPath, type Locale } from './site-map.mjs'

interface PublicHandle {
  publicSite?: boolean
}

/**
 * True while React Router renders or hydrates the SPA shell (__spa-fallback.html). The shell is generated for "/"
 * (with the landing page's matches) but is the document of every /app page, so it must never be treated as a
 * public page: it stays English and keeps robots "noindex, nofollow".
 */
function useSpaShell(): boolean {
  return useContext(UNSAFE_FrameworkContext)?.isSpaMode === true
}

function useLeafIsPublic(): boolean {
  const matches = useMatches()
  return (matches[matches.length - 1]?.handle as PublicHandle | undefined)?.publicSite === true
}

/** True for a prerendered public page (its leaf route exports `handle.publicSite`), false for the /app shell. */
export function usePublicPage(): boolean {
  const spaShell = useSpaShell()
  const leafIsPublic = useLeafIsPublic()
  return !spaShell && leafIsPublic
}

/**
 * <html lang>: the URL's locale on public pages, English for the /app screens (their copy is English) and for the
 * SPA shell as generated at build time. In the browser the SPA may render a public page (the not-found page for an
 * unknown path); root.tsx then updates the attribute after hydration.
 */
export function useDocumentLanguage(): Locale {
  const { pathname } = useLocation()
  const spaShell = useSpaShell()
  const leafIsPublic = useLeafIsPublic()
  if (spaShell && typeof document === 'undefined') return 'en'
  return leafIsPublic ? localeFromPath(pathname) : 'en'
}
