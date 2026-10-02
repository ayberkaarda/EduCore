import { Users } from 'lucide-react'
import { Link } from 'react-router'
import { LoadingState } from '../../../components/Feedback'
import { describeApiError } from '../../../lib/error-messages'
import { useCourses } from '../../courses/hooks'
import { useStudents } from '../hooks'

/** ADMIN dashboard: counts and the first five students of the default listing. */
export default function RegistryOverview({ firstName }: { firstName: string }) {
  const students = useStudents({ search: '', page: 0, size: 5, direction: 'asc', deleted: false })
  const courses = useCourses()

  if (students.isPending || courses.isPending) return <LoadingState size={40} />

  const loadError = students.error ?? courses.error
  const recent = students.data?.content ?? []
  return (
    <section className="student-detail-wrapper" aria-label={`Registry overview for ${firstName}`}>
      <div className="detail-header"><div><h2>Dashboard</h2><p className="text-gray">Registry overview</p></div></div>
      {loadError && <p className="inline-error" role="alert">{describeApiError(loadError, 'Could not load the registry overview. Check your connection and refresh the page.')}</p>}
      <div className="stat-grid">
        <article className="card stat-tile"><p>Total students</p><h3>{students.data?.totalElements ?? 0}</h3><span>Student records</span></article>
        <article className="card stat-tile"><p>Active courses</p><h3>{courses.data?.length ?? 0}</h3><span>Course catalogue</span></article>
      </div>
      <div className="card">
        <div className="split-row"><h3 className="section-title">Recently added students</h3><Link to="/app/students">View all students</Link></div>
        <div className="table-responsive">
          <table>
            <thead><tr><th>Student number</th><th>Name</th></tr></thead>
            <tbody>
              {recent.map(student => (
                <tr key={student.id}>
                  <td>#{student.studentNumber}</td>
                  <td className="text-left">{student.firstName} {student.lastName}</td>
                </tr>
              ))}
              {recent.length === 0 && <tr><td colSpan={2}><div className="empty-state"><Users size={24} /><h4>No students yet.</h4><p>Add a student or run a CSV import.</p></div></td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  )
}
