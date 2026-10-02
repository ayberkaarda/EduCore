import { useAuth } from '../../features/auth/auth-context'
import ProfileScreen from '../../features/enrollments/components/ProfileScreen'
import RegistryOverview from '../../features/students/components/RegistryOverview'

export function meta() {
  return [{ title: 'Dashboard · EduCore' }]
}

/** ADMIN sees the registry overview; USER lands on their own profile, as before. */
export default function DashboardRoute() {
  const { user } = useAuth()
  if (!user) return null
  return user.role === 'ADMIN' ? <RegistryOverview firstName={user.firstName} /> : <ProfileScreen currentUser={user} />
}
