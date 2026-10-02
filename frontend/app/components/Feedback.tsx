import { Loader2, Lock } from 'lucide-react'
import { Link } from 'react-router'

/** Loading state inside a card or table (the CSS adds the visible "Loading" caption). */
export function LoadingState({ size = 32 }: { size?: number }) {
  return (
    <div className="empty-state">
      <Loader2 className="spin text-gray" size={size} aria-hidden="true" />
    </div>
  )
}

/** Full-page loading state while the session is restored. */
export function PageLoading() {
  return (
    <div className="page-loading" role="status">
      <Loader2 className="spin" size={32} aria-hidden="true" />
      <span className="visually-hidden">Loading EduCore</span>
    </div>
  )
}

/** Shown when the signed-in role may not open a route (RBAC matrix; the API enforces the same rule). */
export function AccessDenied() {
  return (
    <section className="card access-denied" role="alert">
      <Lock size={24} aria-hidden="true" />
      <h2>You do not have access to this page</h2>
      <p className="text-gray">This page is for administrators. Your role does not include it.</p>
      <Link to="/app/profile" className="btn-primary">Go to my profile</Link>
    </section>
  )
}
