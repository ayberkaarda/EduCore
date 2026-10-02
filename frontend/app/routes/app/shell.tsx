import { Outlet } from 'react-router'
import AppShell from '../../components/AppShell'
import { RequireAuth } from '../../features/auth/guards'

export default function ShellRoute() {
  return (
    <RequireAuth>
      <AppShell>
        <Outlet />
      </AppShell>
    </RequireAuth>
  )
}
