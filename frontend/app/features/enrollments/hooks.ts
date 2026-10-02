import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { STALE_TIME } from '../../lib/query-client'
import {
  dropAccountEnrollment,
  dropMyEnrollment,
  enrollAccount,
  enrollMe,
  enrollmentKeys,
  listAccountEnrollments,
  listMyEnrollments,
} from './api'

export function useMyEnrollments() {
  return useQuery({ queryKey: enrollmentKeys.mine, queryFn: ({ signal }) => listMyEnrollments(signal), staleTime: STALE_TIME.enrollments })
}

export function useEnrollMe() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (courseId: number) => enrollMe(courseId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: enrollmentKeys.mine }),
  })
}

export function useDropMyEnrollment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (courseId: number) => dropMyEnrollment(courseId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: enrollmentKeys.mine }),
  })
}

export function useAccountEnrollments(accountId: number) {
  return useQuery({
    queryKey: enrollmentKeys.ofAccount(accountId),
    queryFn: ({ signal }) => listAccountEnrollments(accountId, signal),
    staleTime: STALE_TIME.enrollments,
    enabled: Number.isInteger(accountId) && accountId > 0,
  })
}

export function useDropAccountEnrollment(accountId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (courseId: number) => dropAccountEnrollment(accountId, courseId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: enrollmentKeys.ofAccount(accountId) }),
  })
}

export function useEnrollAccount(accountId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (courseId: number) => enrollAccount(accountId, courseId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: enrollmentKeys.ofAccount(accountId) }),
  })
}
