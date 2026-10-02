import { Book, Calendar, PlusCircle } from 'lucide-react'
import type { Course } from '../../../lib/types'

interface EnrollmentBoardProps {
  enrolled: Course[]
  catalogue: Course[]
  busy: boolean
  loading?: boolean
  error?: boolean
  onRetry?: () => void
  emptyEnrolledText: string
  actionLabel: string
  doneLabel: string
  onEnroll: (course: Course) => void
  onDrop?: (course: Course) => void
}

/** Two cards: the account's enrolled courses and the catalogue with an enrol action per course. */
export default function EnrollmentBoard({ enrolled, catalogue, busy, loading, error, onRetry, emptyEnrolledText, actionLabel, doneLabel, onEnroll, onDrop }: EnrollmentBoardProps) {
  const enrolledIds = new Set(enrolled.map(course => course.id))
  return (
    <div className="course-grid">
      <div className="card">
        <h3 className="section-title">Enrolled courses</h3>
        <div className="course-list">
          {loading ? <p role="status">Loading enrollments...</p> : error ? <div role="alert"><p>Enrollments could not be loaded.</p><button type="button" className="btn-secondary" onClick={onRetry}>Retry</button></div> : enrolled.length === 0 ? (
            <p className="empty-state text-gray">{emptyEnrolledText}</p>
          ) : (
            enrolled.map(course => (
              <div key={course.id} className="course-item active">
                <div className="course-info">
                  <h4>{course.name}</h4>
                  <span><Calendar size={14} /> {course.term}</span>
                </div>
                {onDrop ? (
                  <div className="row-actions">
                    <span className="badge success">Enrolled</span>
                    <button type="button" className="btn-secondary" onClick={() => onDrop(course)} disabled={busy || loading || error} aria-label={`Drop ${course.name}`}>Drop</button>
                  </div>
                ) : <span className="badge success">Enrolled</span>}
              </div>
            ))
          )}
        </div>
      </div>

      <div className="card">
        <div className="split-row">
          <h3 className="section-title">Course catalogue</h3>
        </div>
        <div className="course-list">
          {catalogue.length === 0 && <div className="empty-state"><Book size={24} /><h4>No courses yet.</h4><p>Available courses will appear here.</p></div>}
          {catalogue.map(course => {
            const isEnrolled = enrolledIds.has(course.id)
            return (
              <div key={course.id} className={`course-item ${isEnrolled ? 'disabled' : ''}`}>
                <div className="course-info">
                  <h4>{course.name}</h4>
                  <span><Calendar size={14} /> {course.term}</span>
                </div>
                <button type="button" className={`btn-primary add-btn ${isEnrolled ? 'btn-disabled' : ''}`} onClick={() => onEnroll(course)} disabled={isEnrolled || busy || loading || error}>
                  {isEnrolled ? doneLabel : <><PlusCircle size={16} /> {actionLabel}</>}
                </button>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
