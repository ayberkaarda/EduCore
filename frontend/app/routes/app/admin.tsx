import { Outlet } from 'react-router'
import { RequireRole } from '../../features/auth/guards'

/** All nested administration routes call ADMIN-only endpoints. */
export default function AdminRoute() {
  return (
    <RequireRole role="ADMIN">
      <Outlet />
    </RequireRole>
  )
}
