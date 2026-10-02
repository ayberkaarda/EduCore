import { api } from '../../lib/api'
import type { Course } from '../../lib/types'

export const enrollmentKeys = {
  all: ['enrollments'] as const,
  mine: ['enrollments', 'me'] as const,
  ofAccount: (accountId: number) => ['enrollments', 'account', accountId] as const,
}

const accountEnrollmentsPath = (accountId: number) => `/v1/admin/accounts/${encodeURIComponent(accountId)}/enrollments`

// Self-service (any authenticated user; the account is always the caller).
export async function listMyEnrollments(signal?: AbortSignal): Promise<Course[]> {
  const { data } = await api.get<Course[]>('/v1/me/enrollments', { signal })
  return data
}

export async function enrollMe(courseId: number): Promise<Course> {
  const { data } = await api.post<Course>('/v1/me/enrollments', { courseId })
  return data
}

export async function dropMyEnrollment(courseId: number): Promise<void> {
  await api.delete(`/v1/me/enrollments/${encodeURIComponent(courseId)}`)
}

// ADMIN equivalents for any account.
export async function listAccountEnrollments(accountId: number, signal?: AbortSignal): Promise<Course[]> {
  const { data } = await api.get<Course[]>(accountEnrollmentsPath(accountId), { signal })
  return data
}

export async function enrollAccount(accountId: number, courseId: number): Promise<Course> {
  const { data } = await api.post<Course>(accountEnrollmentsPath(accountId), { courseId })
  return data
}

export async function dropAccountEnrollment(accountId: number, courseId: number): Promise<void> {
  await api.delete(`${accountEnrollmentsPath(accountId)}/${encodeURIComponent(courseId)}`)
}
