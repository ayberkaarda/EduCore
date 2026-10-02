import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { AccessDenied, PageLoading } from '../../components/Feedback'
import type { Role } from '../../lib/types'
import { useAuth } from './auth-context'

export const LOGIN_PATH = '/app/login'
export const CHANGE_PASSWORD_PATH = '/app/change-password'

/**
 * Lets only signed-in users through. A user who must change the temporary password is held on the
 * change-password screen until it is done. These guards are cosmetic: the API enforces every rule itself.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status, user } = useAuth()
  const location = useLocation()
  if (status === 'loading') return <PageLoading />
  if (status !== 'authenticated' || !user) {
    return <Navigate to={LOGIN_PATH} replace state={{ from: `${location.pathname}${location.search}` }} />
  }
  if (user.mustChangePassword && location.pathname !== CHANGE_PASSWORD_PATH) {
    return <Navigate to={CHANGE_PASSWORD_PATH} replace />
  }
  return children
}

/** Mirrors docs/security/RBAC_MATRIX.md: `/api/v1/admin/**` routes are ADMIN only. */
export function RequireRole({ role, children }: { role: Role; children: ReactNode }) {
  const { user } = useAuth()
  if (user?.role !== role) return <AccessDenied />
  return children
}
