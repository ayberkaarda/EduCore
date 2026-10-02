import { api } from '../../lib/api'
import type { Account, CreatedStudent, PageResponse } from '../../lib/types'

export interface StudentQuery {
  search: string
  page: number
  size: number
  direction: 'asc' | 'desc'
  deleted: boolean
}

export interface CreateStudentInput {
  firstName: string
  lastName: string
  studentNumber: string
}

export interface UpdateStudentInput {
  firstName: string
  lastName: string
  studentNumber: string
  ipAddress: string
}

export const studentKeys = {
  all: ['students'] as const,
  list: (query: StudentQuery) => ['students', 'list', query] as const,
}

const accountPath = (id: number) => `/v1/admin/accounts/${encodeURIComponent(id)}`

export async function listStudents(query: StudentQuery, signal?: AbortSignal): Promise<PageResponse<Account>> {
  const { data } = await api.get<PageResponse<Account>>('/v1/admin/accounts/students', { params: query, signal })
  return data
}

export async function createStudent(body: CreateStudentInput): Promise<CreatedStudent> {
  const { data } = await api.post<CreatedStudent>('/v1/admin/accounts/students', body)
  return data
}

export async function updateStudent(id: number, body: UpdateStudentInput): Promise<Account> {
  const { data } = await api.put<Account>(accountPath(id), body)
  return data
}

export async function deleteStudent(id: number): Promise<void> {
  await api.delete(accountPath(id))
}
