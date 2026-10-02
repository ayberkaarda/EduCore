import { Navigate } from 'react-router'

/** Unknown paths under /app go to the start page, as they always did. */
export default function UnknownAppRoute() {
  return <Navigate to="/app" replace />
}
