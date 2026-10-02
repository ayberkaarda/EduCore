import type { MetaFunction } from 'react-router'
import { useLocation } from 'react-router'
import { copyFor } from '../../public-site/copy'
import { localeFromPath, localizedPath } from '../../public-site/site-map.mjs'

// Not-found page for unknown public paths. Prerendered as /404 and /en/404 (served by nginx for unknown paths)
// and rendered client-side by the SPA for unknown routes. No loader: it must also render without build data.

export const handle = { publicSite: true, notFound: true }

export const meta: MetaFunction = ({ location }) => {
  const t = copyFor(localeFromPath(location.pathname)).notFound
  return [
    { title: `${t.title} · EduCore` },
    { name: 'description', content: t.description },
    { name: 'robots', content: 'noindex, follow' },
  ]
}

export default function PublicNotFound() {
  const { pathname } = useLocation()
  const locale = localeFromPath(pathname)
  const t = copyFor(locale).notFound
  return (
    <main id="main" className="pub-main">
      <h1>{t.h1}</h1>
      <p className="pub-lead">{t.lead}</p>
      <p className="pub-actions">
        <a className="pub-button pub-button-primary" href={localizedPath(locale, '/')}>{t.homeLink}</a>
        <a className="pub-button" href={localizedPath(locale, '/courses')}>{t.catalogLink}</a>
      </p>
    </main>
  )
}
