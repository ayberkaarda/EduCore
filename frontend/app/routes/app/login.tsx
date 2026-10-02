import { Navigate, useLocation } from 'react-router'
import { PageLoading } from '../../components/Feedback'
import { useAuth } from '../../features/auth/auth-context'
import LoginScreen from '../../features/auth/components/LoginScreen'
import { safeReturnPath } from '../../features/auth/return-path'

export function meta() {
  return [{ title: 'Sign in · EduCore' }]
}

export default function LoginRoute() {
  const { status } = useAuth()
  const location = useLocation()
  const returnTo = safeReturnPath((location.state as { from?: unknown } | null)?.from)
  if (status === 'loading') return <PageLoading />
  if (status === 'authenticated') return <Navigate to={returnTo} replace />
  return <LoginScreen returnTo={returnTo} />
}
