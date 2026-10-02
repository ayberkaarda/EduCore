import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import confirmAction from '../../../components/confirm'
import { toastApiError } from '../../../lib/error-messages'
import { STALE_TIME } from '../../../lib/query-client'
import { toast } from '../../../lib/toast'
import type { Course, UserView } from '../../../lib/types'
import { fetchProfile, profileKeys } from '../../auth/api'
import { useCourses } from '../../courses/hooks'
import { useDropMyEnrollment, useEnrollMe, useMyEnrollments } from '../hooks'
import EnrollmentBoard from './EnrollmentBoard'
import YourData from '../../profile-lifecycle/components/YourData'

/** "My profile": GET /me, own enrolments and the catalogue (any authenticated user). */
export default function ProfileScreen({ currentUser }: { currentUser: UserView }) {
  const profile = useQuery({ queryKey: profileKeys.me, queryFn: ({ signal }) => fetchProfile(signal), staleTime: STALE_TIME.profile })
  const courses = useCourses()
  const enrollments = useMyEnrollments()
  const enroll = useEnrollMe()
  const drop = useDropMyEnrollment()

  const loadError = profile.error ?? courses.error ?? enrollments.error
  useEffect(() => {
    if (loadError) toastApiError(loadError, 'Failed to load course data.')
  }, [loadError])

  const handleEnroll = async (course: Course) => {
    try {
      await enroll.mutateAsync(course.id)
      toast.success('Course selected.')
    } catch (error) {
      toastApiError(error, 'Error occurred while selecting the course.')
    }
  }

  const handleDrop = async (course: Course) => {
    if (!await confirmAction(`Drop the course '${course.name}'?`, { confirmLabel: 'Drop course' })) return
    try {
      await drop.mutateAsync(course.id)
      toast.success('Course dropped.')
    } catch (error) {
      toastApiError(error, 'Error occurred while dropping the course.')
    }
  }

  const details = profile.data
  const role = details?.role ?? currentUser.role
  return (
    <div className="student-detail-wrapper">
      <div className="detail-header">
        <h2>My profile</h2>
        <p className="text-gray">Your details and term courses</p>
      </div>
      <section className="card profile-details">
        <h3>{details ? [details.firstName, details.lastName].filter(Boolean).join(' ') : currentUser.firstName}</h3>
        <p className="mono">{details?.studentNumber || details?.username || details?.id}</p>
        <span className={role === 'ADMIN' ? 'badge' : 'badge neutral'}>{role === 'ADMIN' ? 'Administrator' : 'User'}</span>
      </section>
      <YourData />
      <EnrollmentBoard
        loading={enrollments.isPending}
        error={enrollments.isError}
        onRetry={() => void enrollments.refetch()}
        enrolled={enrollments.data ?? []}
        catalogue={courses.data ?? []}
        busy={enroll.isPending || drop.isPending}
        emptyEnrolledText="You haven't selected any courses yet."
        actionLabel="Select"
        doneLabel="Selected"
        onEnroll={course => void handleEnroll(course)}
        onDrop={course => void handleDrop(course)}
      />
    </div>
  )
}
