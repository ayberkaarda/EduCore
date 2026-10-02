import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { STALE_TIME } from '../../lib/query-client'
import { accountKeys } from '../users/api'
import {
  createStudent,
  deleteStudent,
  listStudents,
  studentKeys,
  updateStudent,
  type CreateStudentInput,
  type StudentQuery,
  type UpdateStudentInput,
} from './api'

/**
 * One query per (search, page, direction, deleted). The AbortSignal cancels a superseded request when the key
 * changes, and each key has its own cache entry, so a slow older response can never overwrite newer results.
 */
export function useStudents(query: StudentQuery) {
  return useQuery({
    queryKey: studentKeys.list(query),
    queryFn: ({ signal }) => listStudents(query, signal),
    staleTime: STALE_TIME.students,
  })
}

function useInvalidateAccounts() {
  const queryClient = useQueryClient()
  return async () => {
    await queryClient.invalidateQueries({ queryKey: studentKeys.all })
    await queryClient.invalidateQueries({ queryKey: accountKeys.all })
  }
}

export function useCreateStudent() {
  const invalidate = useInvalidateAccounts()
  return useMutation({ mutationFn: (body: CreateStudentInput) => createStudent(body), onSuccess: invalidate })
}

export function useUpdateStudent() {
  const invalidate = useInvalidateAccounts()
  return useMutation({ mutationFn: ({ id, body }: { id: number; body: UpdateStudentInput }) => updateStudent(id, body), onSuccess: invalidate })
}

export function useDeleteStudent() {
  const invalidate = useInvalidateAccounts()
  return useMutation({ mutationFn: (id: number) => deleteStudent(id), onSuccess: invalidate })
}
