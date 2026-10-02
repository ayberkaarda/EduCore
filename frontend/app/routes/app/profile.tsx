import { useAuth } from '../../features/auth/auth-context'
import ProfileScreen from '../../features/enrollments/components/ProfileScreen'

export function meta() {
  return [{ title: 'My profile · EduCore' }]
}

export default function ProfileRoute() {
  const { user } = useAuth()
  return user ? <ProfileScreen currentUser={user} /> : null
}
