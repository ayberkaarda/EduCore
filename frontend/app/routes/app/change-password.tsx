import ChangePasswordScreen from '../../features/auth/components/ChangePasswordScreen'
import { RequireAuth } from '../../features/auth/guards'

export function meta() {
  return [{ title: 'Change password · EduCore' }]
}

export default function ChangePasswordRoute() {
  return (
    <RequireAuth>
      <ChangePasswordScreen />
    </RequireAuth>
  )
}
