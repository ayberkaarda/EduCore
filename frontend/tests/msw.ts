import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type { Role, SessionPayload, UserView } from '../app/lib/types'

/** jsdom runs at http://localhost:3000, so the default base URL "/api" resolves to this origin. */
export const API = 'http://localhost:3000/api/v1'

export const server = setupServer()

/** Test transport marker derived from Axios withCredentials in setup.ts. */
export const SESSION_CREDENTIAL_HEADER = 'X-Test-Session-Credentials'

export const adminUser: UserView = { id: 1, firstName: 'Ada', role: 'ADMIN', mustChangePassword: false, status: 'ACTIVE' }
export const regularUser: UserView = { id: 2, firstName: 'Ali', role: 'USER', mustChangePassword: false, status: 'ACTIVE' }
export const passwordChangeUser: UserView = { ...regularUser, mustChangePassword: true }
export const pendingDeletionUser: UserView = { ...regularUser, status: 'PENDING_DELETION' }

export function retryProblem(status: 423 | 429, seconds: number) {
  return HttpResponse.json({ code: status === 423 ? 'auth/account-locked' : 'auth/too-many-attempts' }, { status, headers: { 'Retry-After': String(seconds) } })
}

export function sessionFor(user: UserView, accessToken = `token-${user.id}`): SessionPayload {
  return { accessToken, expiresIn: 900, user }
}

/** An RFC 9457 problem response as the backend sends it. */
export function problem(status: number, code: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `/problems/${code}`, title: 'The request failed.', status, detail: 'Fixed sentence.', instance: '/api/v1', code, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

/** Handlers for a browser that still holds a valid refresh cookie for `user`. */
function sessionRequestError(request: Request) {
  const origin = request.headers.get('Origin')
  // Same-origin browser requests may omit Origin; explicit origins must be allow-listed.
  if (origin && origin !== 'http://localhost:3000') return problem(403, 'auth/origin-rejected')
  // jsdom/MSW's XHR conversion does not reliably preserve withCredentials.
  if (request.headers.get(SESSION_CREDENTIAL_HEADER) !== 'include') return problem(401, 'auth/invalid-refresh-token')
  return null
}

export function sessionHandlers(user: UserView, role: Role = user.role) {
  const current = { ...user, role }
  return [
    http.post(`${API}/auth/refresh`, ({ request }) => sessionRequestError(request) ?? HttpResponse.json(sessionFor(current))),
    http.get(`${API}/auth/me`, () => HttpResponse.json(current)),
    http.post(`${API}/auth/logout`, ({ request }) => sessionRequestError(request) ?? new HttpResponse(null, { status: 204 })),
    http.get(`${API}/weather`, () => HttpResponse.json([])),
  ]
}

/** Handlers for a browser without a session (no or expired refresh cookie). */
export function anonymousHandlers() {
  return [
    http.post(`${API}/auth/refresh`, () => problem(401, 'auth/invalid-refresh-token')),
    http.get(`${API}/weather`, () => HttpResponse.json([])),
  ]
}
