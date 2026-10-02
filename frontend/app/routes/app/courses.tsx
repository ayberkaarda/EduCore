import { useAuth } from '../../features/auth/auth-context'
import CoursesScreen from '../../features/courses/components/CoursesScreen'

export function meta() {
  return [{ title: 'Courses · EduCore' }]
}

export default function CoursesRoute() {
  const { user } = useAuth()
  return <CoursesScreen isAdmin={user?.role === 'ADMIN'} />
}
