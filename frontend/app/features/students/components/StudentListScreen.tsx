import { ArrowUpDown, ChevronRight, Edit, Plus, Search, Trash2, User } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import confirmAction from '../../../components/confirm'
import { LoadingState } from '../../../components/Feedback'
import { useDebounce } from '../../../components/useDebounce'
import { toastApiError } from '../../../lib/error-messages'
import { toast } from '../../../lib/toast'
import type { Account } from '../../../lib/types'
import { useDeleteStudent, useStudents } from '../hooks'
import AddStudentDialog from './AddStudentDialog'
import EditStudentDialog from './EditStudentDialog'
import TemporaryPasswordDialog, { type CreatedStudentSummary } from './TemporaryPasswordDialog'
import AccountLifecycleActions, { AccountStatusBadge } from '../../profile-lifecycle/components/AccountLifecycleActions'

export const STUDENT_PAGE_SIZE = 8

/** ADMIN: search, page, create, edit and soft-delete students (GET/POST/PUT/DELETE /admin/accounts...). */
export default function StudentListScreen() {
  const navigate = useNavigate()
  const [searchTerm, setSearchTerm] = useState('')
  const search = useDebounce(searchTerm, 500)
  const [page, setPage] = useState(0)
  const [direction, setDirection] = useState<'asc' | 'desc'>('asc')
  const [showDeleted, setShowDeleted] = useState(false)
  const [isAddOpen, setIsAddOpen] = useState(false)
  const [editing, setEditing] = useState<Account | null>(null)
  const [createdStudent, setCreatedStudent] = useState<CreatedStudentSummary | null>(null)
  const [announcement, setAnnouncement] = useState('')

  // A new search goes back to the first page (state adjusted during render).
  const [lastSearch, setLastSearch] = useState(search)
  if (lastSearch !== search) {
    setLastSearch(search)
    setPage(0)
  }

  const students = useStudents({ search, page, size: STUDENT_PAGE_SIZE, direction, deleted: showDeleted })
  const deleteStudent = useDeleteStudent()

  useEffect(() => {
    if (students.error) toastApiError(students.error, 'Failed to load students')
  }, [students.error])

  const rows = students.data?.content ?? []
  const totalPages = students.data?.totalPages ?? 1
  if (students.isSuccess && !students.isFetching && page > Math.max(0, totalPages - 1)) {
    setPage(Math.max(0, totalPages - 1))
  }

  const handleDelete = async (student: Account) => {
    if (!await confirmAction(`Delete student ${student.studentNumber || student.id}? This hides the record.`)) return
    try {
      await deleteStudent.mutateAsync(student.id)
      toast.success('Student deleted.')
    } catch (error) {
      toastApiError(error, 'Failed to delete student.')
    }
  }

  return (
    <div className="card">
      <span className="temporary-password-announcement" role="status" aria-live="polite" aria-atomic="true">{announcement}</span>
      {createdStudent && <TemporaryPasswordDialog student={createdStudent} onSaved={() => setCreatedStudent(null)} />}
      <div className="detail-header">
        <div>
          <h2>Students</h2>
          <p className="text-gray">Search, edit and enrol students</p>
        </div>

        <div className="search-box">
          <Search size={20} aria-hidden="true" />
          <input type="text" placeholder="Search by name or number" value={searchTerm} maxLength={100}
            onChange={event => setSearchTerm(event.target.value)} aria-label="Search by name or number" />
        </div>

        <div className="inline-fields">
          <button type="button" className="btn-secondary" aria-pressed={showDeleted} onClick={() => { setShowDeleted(!showDeleted); setPage(0) }}>
            {showDeleted ? 'Show active' : 'Show deleted'}
          </button>
          <button type="button" className="btn-primary" onClick={() => setIsAddOpen(true)}>
            <Plus size={18} /> Add student
          </button>
        </div>
      </div>

      <div className="table-responsive">
        {students.isPending ? <LoadingState /> : rows.length === 0 ? (
          <div className="empty-state">
            <User size={24} />
            <h4>{search ? 'No students match your search.' : showDeleted ? 'No deleted students.' : 'No students yet.'}</h4>
            <p>{search ? 'Try another name or number.' : 'Add a student or run a CSV import.'}</p>
            {search && <button type="button" className="btn-secondary" onClick={() => setSearchTerm('')}>Clear search</button>}
          </div>
        ) : (
          <>
            <table>
              <thead>
                <tr>
                  <th>Student number</th>
                  <th aria-sort={direction === 'asc' ? 'ascending' : 'descending'}>
                    <button type="button" className="sort-button" onClick={() => setDirection(previous => (previous === 'asc' ? 'desc' : 'asc'))}>Name <ArrowUpDown size={14} /></button>
                  </th>
                  <th>IP address</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(student => (
                  <tr key={student.id} className={showDeleted ? 'deleted-row' : ''}>
                    <td>#{student.studentNumber || 'N/A'}</td>
                    <td>
                      <div className="inline-group">
                        <span className="avatar">{student.firstName?.slice(0, 1)}{student.lastName?.slice(0, 1)}</span>
                        {student.firstName} {student.lastName}
                        <AccountStatusBadge account={student} />
                      </div>
                    </td>
                    <td>{student.ipAddress ? <span className="mono">{student.ipAddress}</span> : <span className="text-gray">Not assigned</span>}</td>
                    <td>
                      <div className="row-actions">
                        <AccountLifecycleActions account={student} />
                        <button type="button" className="btn-secondary" disabled={showDeleted} onClick={() => navigate(`/app/students/${student.id}`)}>
                          Courses <ChevronRight size={16} />
                        </button>
                        {!showDeleted && (
                          <>
                            <button type="button" className="btn-secondary" onClick={() => setEditing(student)} title="Edit"><Edit size={16} />Edit</button>
                            <button type="button" className="btn-secondary" onClick={() => void handleDelete(student)} title="Delete"><Trash2 size={16} />Delete</button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </div>

      {(totalPages > 1 || page > 0) && <div className="split-row">
        <button type="button" className="btn-secondary" onClick={() => setPage(previous => Math.max(0, previous - 1))} disabled={page === 0}>Previous</button>
        <span className="text-gray">Page {page + 1} of {Math.max(1, totalPages)}</span>
        <button type="button" className="btn-secondary" onClick={() => setPage(previous => previous + 1)} disabled={page >= totalPages - 1}>Next</button>
      </div>}
      {isAddOpen && (
        <AddStudentDialog
          onCancel={() => setIsAddOpen(false)}
          onCreated={(created, submitted) => {
            const summary: CreatedStudentSummary = {
              firstName: created.firstName ?? submitted.firstName,
              lastName: created.lastName ?? submitted.lastName,
              studentNumber: created.studentNumber ?? submitted.studentNumber,
              username: created.username,
              temporaryPassword: created.temporaryPassword,
            }
            setIsAddOpen(false)
            setCreatedStudent(summary)
            setAnnouncement(`Student created: ${summary.firstName} ${summary.lastName}, student number ${summary.studentNumber}. Save the temporary password shown in the dialog.`)
            toast.success('Student added.')
          }}
        />
      )}

      {editing && (
        <EditStudentDialog
          student={editing}
          pageStudents={rows}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            toast.success('Student updated.')
          }}
        />
      )}
    </div>
  )
}
