import { Navigate, useParams } from 'react-router'
import StudentDetailScreen from '../../features/students/components/StudentDetailScreen'

export function meta() {
  return [{ title: 'Student courses · EduCore' }]
}

export default function StudentDetailRoute() {
  const { id } = useParams()
  const accountId = Number(id)
  // Path ids are positive integers (the API answers 400 otherwise).
  if (!Number.isInteger(accountId) || accountId <= 0) return <Navigate to="/app/students" replace />
  return <StudentDetailScreen key={accountId} accountId={accountId} />
}
