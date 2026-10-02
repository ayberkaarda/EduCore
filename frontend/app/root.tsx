import '@fontsource/ibm-plex-sans/400.css'
import '@fontsource/ibm-plex-sans/500.css'
import '@fontsource/ibm-plex-sans/600.css'
import '@fontsource/ibm-plex-mono/400.css'
import '@fontsource/ibm-plex-mono/500.css'
import './styles/index.css'
import { QueryClientProvider } from '@tanstack/react-query'
import { useEffect, useState, type ReactNode } from 'react'
import { isRouteErrorResponse, Link, Links, Meta, Outlet, Scripts, useRouteError } from 'react-router'
import { PageLoading } from './components/Feedback'
import Toaster from './components/Toaster'
import RouteFocus from './components/RouteFocus'
import { createQueryClient } from './lib/query-client'
import { applyTheme, readStoredTheme } from './lib/theme-storage'
import { useDocumentLanguage, usePublicPage } from './public-site/document'

/**
 * The HTML document (replaces index.html). No inline scripts or styles are authored here.
 * Public pages (handle.publicSite) get <html lang> from their URL (tr at the root, en under /en); every other
 * document, including the /app SPA shell, is English and carries robots "noindex, nofollow" (docs/seo/META.md).
 */
export function Layout({ children }: { children: ReactNode }) {
  const lang = useDocumentLanguage()
  const publicPage = usePublicPage()
  // Static HTML cannot be re-rendered by the client for the attribute, so keep it in sync after navigation.
  useEffect(() => {
    document.documentElement.lang = lang
  }, [lang])
  return (
    <html lang={lang} suppressHydrationWarning>
      <head>
        <meta charSet="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <meta name="theme-color" content="#0E2A34" />
        <link rel="icon" type="image/svg+xml" href="/brand/favicon.svg" />
        {!publicPage && <meta name="robots" content="noindex, nofollow" />}
        <Meta />
        <Links />
      </head>
      <body>
        {children}
        <Scripts />
      </body>
    </html>
  )
}

export function meta() {
  return [
    { title: 'EduCore' },
    { name: 'description', content: 'EduCore student and course registry. Records you can trust.' },
  ]
}

export default function App() {
  const [queryClient] = useState(createQueryClient)
  const publicPage = usePublicPage()
  // The stored theme is applied after hydration (the static HTML cannot know it, and no inline script runs).
  useEffect(() => applyTheme(readStoredTheme()), [])
  // Public pages are static documents: no query cache, focus manager or toast region.
  if (publicPage) return <Outlet />
  return (
    <QueryClientProvider client={queryClient}>
      <Outlet />
      <RouteFocus />
      <Toaster />
    </QueryClientProvider>
  )
}

/** Rendered into __spa-fallback.html and shown until the client app has loaded. */
export function HydrateFallback() {
  return <PageLoading />
}

export function ErrorBoundary() {
  const error = useRouteError()
  const notFound = isRouteErrorResponse(error) && error.status === 404
  return (
    <main className="auth-page">
      <section className="auth-card" role="alert">
        <h2>{notFound ? 'Page not found' : 'Something went wrong'}</h2>
        <p className="text-gray">{notFound ? 'The address does not match any EduCore page.' : 'Reload the page. If the problem persists, contact the administrator.'}</p>
        <Link to="/app" className="btn-primary">Open EduCore</Link>
      </section>
    </main>
  )
}
