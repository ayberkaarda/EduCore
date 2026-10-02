import { ArrowLeft } from 'lucide-react'
import { useEffect } from 'react'
import { useNavigate } from 'react-router'
import confirmAction from '../../../components/confirm'
import { toastApiError } from '../../../lib/error-messages'
import { toast } from '../../../lib/toast'
import type { Course } from '../../../lib/types'
import { useCourses } from '../../courses/hooks'
import EnrollmentBoard from '../../enrollments/components/EnrollmentBoard'
import { useAccountEnrollments, useDropAccountEnrollment, useEnrollAccount } from '../../enrollments/hooks'
import UnlockLoginButton from '../../profile-lifecycle/components/UnlockLoginButton'

/** ADMIN: one student's enrolments and the catalogue (GET/POST /admin/accounts/{id}/enrollments). */
export default function StudentDetailScreen({ accountId }: { accountId: number }) {
  const navigate = useNavigate()
  const courses = useCourses()
  const enrollments = useAccountEnrollments(accountId)
  const enroll = useEnrollAccount(accountId)
  const drop = useDropAccountEnrollment(accountId)

  const loadError = courses.error ?? enrollments.error
  useEffect(() => {
    if (loadError) toastApiError(loadError, 'Failed to load course data.')
  }, [loadError])

  const handleEnroll = async (course: Course) => {
    try {
      await enroll.mutateAsync(course.id)
      toast.success('Course added.')
    } catch (error) {
      toastApiError(error, 'Error occurred while adding the course.')
    }
  }

  const handleDrop = async (course: Course) => {
    if (!await confirmAction(`Drop the course '${course.name}' for this student?`, { confirmLabel: 'Drop course' })) return
    try {
      await drop.mutateAsync(course.id)
      toast.success('Course dropped.')
    } catch (error) {
      toastApiError(error, 'Error occurred while dropping the course.')
    }
  }

  return (
    <div className="student-detail-wrapper">
      <button type="button" className="btn-secondary back-btn" onClick={() => navigate(-1)}>
        <ArrowLeft size={18} /> Back
      </button>
      <div className="detail-header">
        <h2>Student courses</h2>
        <p className="text-gray">Record identifier: {accountId}</p>
        <UnlockLoginButton accountId={accountId} />
      </div>
      <EnrollmentBoard
        loading={enrollments.isPending}
        error={enrollments.isError}
        onRetry={() => void enrollments.refetch()}
        enrolled={enrollments.data ?? []}
        catalogue={courses.data ?? []}
        busy={enroll.isPending || drop.isPending}
        emptyEnrolledText="The student has not enrolled in any courses yet."
        actionLabel="Enrol"
        doneLabel="Enrolled"
        onEnroll={course => void handleEnroll(course)}
        onDrop={course => void handleDrop(course)}
      />
    </div>
  )
}
