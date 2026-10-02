import { Book, Edit, Plus, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import confirmAction from '../../../components/confirm'
import { LoadingState } from '../../../components/Feedback'
import { toastApiError } from '../../../lib/error-messages'
import { toast } from '../../../lib/toast'
import type { Course } from '../../../lib/types'
import { useCourses, useCreateCourse, useDeleteCourse, useUpdateCourse } from '../hooks'
import CourseFormDialog from './CourseFormDialog'

const EMPTY_COURSE = { name: '', term: '', instructor: '' }

/** Course catalogue: everyone can read it (GET /courses); only ADMIN sees the write actions. */
export default function CoursesScreen({ isAdmin }: { isAdmin: boolean }) {
  const courses = useCourses()
  const createCourse = useCreateCourse()
  const updateCourse = useUpdateCourse()
  const deleteCourse = useDeleteCourse()
  const [isAddOpen, setIsAddOpen] = useState(false)
  const [editing, setEditing] = useState<Course | null>(null)

  useEffect(() => {
    if (courses.error) toastApiError(courses.error, 'Failed to load courses.')
  }, [courses.error])

  const list = courses.data ?? []

  const handleDelete = async (course: Course) => {
    if (!await confirmAction(`Delete course '${course.name}'?`)) return
    try {
      await deleteCourse.mutateAsync(course.id)
      toast.success('Course deleted.')
    } catch (error) {
      toastApiError(error, 'Failed to delete course.')
    }
  }

  return (
    <div className="card">
      <div className="detail-header">
        <div>
          <h2>Courses</h2>
          <p className="text-gray">Catalogue of active courses</p>
        </div>
        {isAdmin && (
          <button type="button" className="btn-primary" onClick={() => setIsAddOpen(true)}>
            <Plus size={18} /> Add course
          </button>
        )}
      </div>

      <div className="table-responsive">
        {courses.isPending ? <LoadingState /> : (
          <table>
            <thead><tr><th>Course</th><th>Term</th><th>Instructor</th><th>Actions</th></tr></thead>
            <tbody>
              {list.map(course => (
                <tr key={course.id}>
                  <td>{course.name}</td>
                  <td>{course.term}</td>
                  <td>{course.instructor || 'Not assigned'}</td>
                  <td>
                    {isAdmin && (
                      <div className="row-actions">
                        <button type="button" className="btn-secondary" onClick={() => setEditing(course)}><Edit size={16} />Edit</button>
                        <button type="button" className="btn-secondary" onClick={() => void handleDelete(course)}><Trash2 size={16} />Delete</button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
              {list.length === 0 && (
                <tr><td colSpan={4}><div className="empty-state"><Book size={24} /><h4>No courses yet.</h4><p>Add the first course or run a CSV import.</p></div></td></tr>
              )}
            </tbody>
          </table>
        )}
      </div>

      {isAddOpen && (
        <CourseFormDialog
          title="Add course"
          submitLabel="Save"
          idPrefix="course-add"
          initialValues={EMPTY_COURSE}
          failureMessage="Could not add the course."
          onCancel={() => setIsAddOpen(false)}
          onSubmit={async values => {
            await createCourse.mutateAsync(values)
            toast.success('Course added.')
            setIsAddOpen(false)
          }}
        />
      )}

      {editing && (
        <CourseFormDialog
          title="Edit course"
          submitLabel="Save changes"
          idPrefix="course-edit"
          initialValues={{ name: editing.name, term: editing.term ?? '', instructor: editing.instructor ?? '' }}
          failureMessage="Failed to update course."
          onCancel={() => setEditing(null)}
          onSubmit={async values => {
            await updateCourse.mutateAsync({ id: editing.id, body: values })
            toast.success('Course updated.')
            setEditing(null)
          }}
        />
      )}
    </div>
  )
}
