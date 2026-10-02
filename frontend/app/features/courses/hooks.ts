import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { STALE_TIME } from '../../lib/query-client'
import { enrollmentKeys } from '../enrollments/api'
import { courseKeys, createCourse, deleteCourse, listCourses, updateCourse, type CourseInput } from './api'

export function useCourses() {
  return useQuery({ queryKey: courseKeys.all, queryFn: ({ signal }) => listCourses(signal), staleTime: STALE_TIME.courses })
}

function useInvalidateCourses() {
  const queryClient = useQueryClient()
  return async () => {
    await queryClient.invalidateQueries({ queryKey: courseKeys.all })
    // Enrolment lists embed course names, so they are refreshed too.
    await queryClient.invalidateQueries({ queryKey: enrollmentKeys.all })
  }
}

export function useCreateCourse() {
  const invalidate = useInvalidateCourses()
  return useMutation({ mutationFn: (body: CourseInput) => createCourse(body), onSuccess: invalidate })
}

export function useUpdateCourse() {
  const invalidate = useInvalidateCourses()
  return useMutation({ mutationFn: ({ id, body }: { id: number; body: CourseInput }) => updateCourse(id, body), onSuccess: invalidate })
}

export function useDeleteCourse() {
  const invalidate = useInvalidateCourses()
  return useMutation({ mutationFn: (id: number) => deleteCourse(id), onSuccess: invalidate })
}
