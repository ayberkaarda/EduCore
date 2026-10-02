import { Navigate, useLocation } from 'react-router'
import { legacyTarget } from './legacy-paths'

/** Pre-P8 bookmarks keep working: the browser is sent to the /app/* equivalent. */
export default function LegacyRedirect() {
  const location = useLocation()
  return <Navigate to={`${legacyTarget(location.pathname)}${location.search}`} replace />
}
