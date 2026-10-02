import { api } from '../../lib/api'
import type { Course } from '../../lib/types'

export interface CourseInput {
  name: string
  term: string
  instructor: string
}

export const courseKeys = { all: ['courses'] as const }

const coursePath = (id: number) => `/v1/admin/courses/${encodeURIComponent(id)}`

export async function listCourses(signal?: AbortSignal): Promise<Course[]> {
  const { data } = await api.get<Course[]>('/v1/courses', { signal })
  return data
}

export async function createCourse(body: CourseInput): Promise<Course> {
  const { data } = await api.post<Course>('/v1/admin/courses', body)
  return data
}

export async function updateCourse(id: number, body: CourseInput): Promise<Course> {
  const { data } = await api.put<Course>(coursePath(id), body)
  return data
}

export async function deleteCourse(id: number): Promise<void> {
  await api.delete(coursePath(id))
}
