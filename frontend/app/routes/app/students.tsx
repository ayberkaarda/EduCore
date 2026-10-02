import StudentListScreen from '../../features/students/components/StudentListScreen'

export function meta() {
  return [{ title: 'Students · EduCore' }]
}

export default function StudentsRoute() {
  return <StudentListScreen />
}
