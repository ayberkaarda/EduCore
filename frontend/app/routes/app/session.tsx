import { Outlet } from 'react-router'
import { AuthProvider } from '../../features/auth/AuthProvider'

/** Every /app route shares one in-memory session, restored from the refresh cookie on load. */
export default function SessionRoute() {
  return (
    <AuthProvider>
      <Outlet />
    </AuthProvider>
  )
}
