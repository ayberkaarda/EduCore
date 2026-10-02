import { randomBytes } from 'node:crypto'
import { expect, request, type APIRequestContext } from '@playwright/test'

/** Edge origin of the stack under test (nginx serves the site and proxies /api/). */
export const BASE_URL = (process.env.E2E_BASE_URL ?? process.env.BASE_URL ?? 'http://localhost:3000').replace(/\/+$/, '')

/**
 * Seeded ADMIN of the dev profile (db/seed/dev). The default password is the public demo password of the dev seed,
 * which exists only in the dev and test profiles; set E2E_ADMIN_PASSWORD for any other stack.
 */
export const ADMIN = {
  username: process.env.E2E_ADMIN_USERNAME ?? 'admin',
  password: process.env.E2E_ADMIN_PASSWORD ?? 'REMOVED-DB-PASSWORD',
}

export interface SessionUser {
  id: number
  firstName: string
  role: 'ADMIN' | 'USER'
  mustChangePassword: boolean
  status: 'ACTIVE' | 'PENDING_DELETION'
}

export interface Session {
  accessToken: string
  expiresIn: number
  user: SessionUser
}

export interface CreatedStudent {
  id: number
  username: string
  firstName: string
  lastName: string | null
  temporaryPassword: string
}

export interface Credentials {
  id: number
  username: string
  password: string
  firstName: string
  lastName: string
}

/** Short unique suffix (letters and digits) for names created by one test run. */
export function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${randomBytes(3).toString('hex')}`
}

/** Letters only, for person names (the name pattern rejects digits). */
export function uniqueLetters(length = 8): string {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz'
  return [...randomBytes(length)].map(byte => alphabet[byte % alphabet.length]).join('')
}

/** A password that satisfies the password policy (12 to 128 characters, not a common password). */
export function strongPassword(): string {
  return `E2e-${randomBytes(9).toString('base64url')}-Pass`
}

/** A fresh API context against the edge, with the Origin header the auth endpoints expect from the browser. */
export function newApiContext(): Promise<APIRequestContext> {
  return request.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { Origin: BASE_URL, Accept: 'application/json' } })
}

export const bearer = (token: string) => ({ Authorization: `Bearer ${token}` })

/**
 * POST /api/v1/auth/login. The backend allows 10 login attempts per client IP and minute; every test signs in a few
 * times, so a 429 is retried until the bucket refills (expect.poll, no fixed sleep). A rejected attempt consumes no
 * token, so the retries do not extend the wait.
 */
export async function apiLogin(api: APIRequestContext, username: string, password: string): Promise<Session> {
  let status = 0
  let body: unknown = null
  await expect.poll(async () => {
    const response = await api.post('/api/v1/auth/login', { data: { username, password } })
    status = response.status()
    body = await response.json().catch(() => null)
    return status
  }, { message: `login of ${username} stays rate limited`, timeout: 120_000, intervals: [2_000, 5_000, 10_000] }).not.toBe(429)
  expect(status, `login of ${username}: ${JSON.stringify(body)}`).toBe(200)
  return body as Session
}

let adminSession: { api: APIRequestContext; session: Session; at: number } | null = null

/** One API session of the seeded ADMIN per worker (access tokens live 15 minutes; renewed after 10). */
export async function adminApi(): Promise<{ api: APIRequestContext; token: string }> {
  if (!adminSession || Date.now() - adminSession.at > 10 * 60_000) {
    const api = adminSession?.api ?? await newApiContext()
    adminSession = { api, session: await apiLogin(api, ADMIN.username, ADMIN.password), at: Date.now() }
  }
  return { api: adminSession.api, token: adminSession.session.accessToken }
}

/** POST /api/v1/admin/accounts/students: a USER with a one-time temporary password. */
export async function createStudent(firstName: string, lastName: string): Promise<CreatedStudent> {
  const { api, token } = await adminApi()
  const response = await api.post('/api/v1/admin/accounts/students', { headers: bearer(token), data: { firstName, lastName } })
  expect(response.status(), await response.text()).toBe(201)
  return await response.json() as CreatedStudent
}

/** A USER who has already replaced the temporary password (first sign-in and POST /api/v1/auth/password). */
export async function createActiveUser(label: string): Promise<Credentials> {
  // Person names allow letters, spaces, apostrophes, dots and hyphens only (no digits).
  const firstName = `Endtoend ${label}`
  const lastName = `User ${uniqueLetters()}`
  const created = await createStudent(firstName, lastName)
  const password = strongPassword()
  const api = await newApiContext()
  try {
    const session = await apiLogin(api, created.username, created.temporaryPassword)
    expect(session.user.mustChangePassword).toBe(true)
    const changed = await api.post('/api/v1/auth/password', {
      headers: bearer(session.accessToken),
      data: { currentPassword: created.temporaryPassword, newPassword: password },
    })
    expect(changed.status(), await changed.text()).toBe(200)
  } finally {
    await api.dispose()
  }
  return { id: created.id, username: created.username, password, firstName, lastName }
}

export interface Course {
  id: number
  name: string
  term: string
  slug: string | null
  published: boolean
}

/** POST /api/v1/admin/courses. */
export async function createCourse(name: string, extra: Record<string, unknown> = {}): Promise<Course> {
  const { api, token } = await adminApi()
  const response = await api.post('/api/v1/admin/courses', { headers: bearer(token), data: { name, term: '2026/1', instructor: 'E2E Instructor', ...extra } })
  expect(response.status(), await response.text()).toBe(201)
  return await response.json() as Course
}

/** DELETE /api/v1/admin/courses/{id} (cleanup; the course must have no enrolments left). */
export async function deleteCourse(id: number): Promise<void> {
  const { api, token } = await adminApi()
  const response = await api.delete(`/api/v1/admin/courses/${id}`, { headers: bearer(token) })
  expect(response.status(), await response.text()).toBe(204)
}

/** GET /api/v1/admin/accounts/{id}/enrollments. */
export async function enrollmentsOf(accountId: number): Promise<Course[]> {
  const { api, token } = await adminApi()
  const response = await api.get(`/api/v1/admin/accounts/${accountId}/enrollments`, { headers: bearer(token) })
  expect(response.status()).toBe(200)
  return await response.json() as Course[]
}
