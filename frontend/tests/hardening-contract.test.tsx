import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, it } from 'vitest'
import { api, clearSession, getAccessToken } from '../app/lib/api'
import { API, adminUser, anonymousHandlers, passwordChangeUser, pendingDeletionUser, problem, regularUser, retryProblem, server, sessionFor, sessionHandlers } from './msw'
import { renderApp } from './render-app'
import { fetchWeather } from '../app/features/weather/api'

it.each([423, 429] as const)('counts down a %s Retry-After and blocks repeat sign-ins', async status => {
  let attempts = 0
  server.use(...anonymousHandlers(), http.post(`${API}/auth/login`, () => { attempts++; return retryProblem(status, 2) }))
  renderApp('/app/login')
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Username (required)'), 'ali')
  await user.type(screen.getByLabelText('Password (required)'), 'password')
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(`${status === 423 ? 'The account is locked.' : 'Too many attempts.'} Try again in 2 seconds.`)
  expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled(), { timeout: 4000 })
  expect(attempts).toBe(1)
})

it.each([false, true])('isolates password-change scope (server rejection: %s)', async reject => {
  let calls = 0
  let logouts = 0
  server.use(...sessionHandlers(reject ? regularUser : passwordChangeUser),
    http.get(`${API}/courses`, () => { calls++; return problem(403, 'account/password-change-required') }),
    http.post(`${API}/auth/logout`, () => { logouts++; return new HttpResponse(null, { status: 204 }) }))
  renderApp('/app/courses')
  await screen.findByRole('heading', { name: 'Change your temporary password' })
  expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
  await expect(api.get('/v1/me')).rejects.toMatchObject({ code: 'account/password-change-required' })
  expect(calls).toBe(reject ? 1 : 0)
  expect(logouts).toBe(0)
  expect(getAccessToken()).toBe('token-2')
})

it.each([400, 423, 429] as const)('keeps restore errors in the password form (%s)', async status => {
  server.use(...sessionHandlers(pendingDeletionUser),
    http.get(`${API}/me`, () => HttpResponse.json({ deleteAfter: '2026-11-01T12:00:00Z' })),
    http.post(`${API}/me/restore`, () => status === 400 ? problem(400, 'auth/invalid-current-password') : retryProblem(status, 7)))
  renderApp('/app/profile')
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Current password (required)'), 'wrong')
  await user.click(screen.getByRole('button', { name: 'Restore' }))
  if (status === 400) expect(await screen.findByText('The current password is incorrect.')).toBeInTheDocument()
  else expect(await screen.findByRole('alert')).toHaveTextContent('Try again in 7 seconds.')
  expect(getAccessToken()).toBe('token-2')
})

it.each(['/app/users', '/app/students/10'])('unlocks sign-in from %s', async path => {
  let calls = 0
  server.use(...sessionHandlers(adminUser),
    http.get(`${API}/admin/accounts`, () => HttpResponse.json({ content: [{ id: 10, username: 'student10', firstName: 'Ali', lastName: 'Veli', role: 'USER', status: 'ACTIVE' }], page: 0, size: 20, totalElements: 1, totalPages: 1 })),
    http.get(`${API}/courses`, () => HttpResponse.json([])),
    http.get(`${API}/admin/accounts/10/enrollments`, () => HttpResponse.json([])),
    http.post(`${API}/admin/accounts/10/unlock-login`, () => { calls++; return new HttpResponse(null, { status: 204 }) }))
  renderApp(path)
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Unlock sign-in' }))
  await waitFor(() => expect(calls).toBe(1))
  expect(await screen.findByText('Sign-in unlocked.')).toBeInTheDocument()
})

it('hides unlock sign-in from non-admins', async () => {
  server.use(...sessionHandlers(regularUser))
  renderApp('/app/users')
  await screen.findByRole('heading', { name: 'You do not have access to this page' })
  expect(screen.queryByRole('button', { name: 'Unlock sign-in' })).not.toBeInTheDocument()
})

it('uses the password-change response as the next session', async () => {
  server.use(...sessionHandlers(passwordChangeUser),
    http.post(`${API}/auth/password`, () => HttpResponse.json(sessionFor(regularUser, 'changed-token'))),
    http.get(`${API}/courses`, () => HttpResponse.json([])))
  renderApp('/app/courses')
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Current password (required)'), 'temporary')
  await user.type(screen.getByLabelText('New password (required)'), 'a-long-new-password')
  await user.type(screen.getByLabelText('Repeat new password (required)'), 'a-long-new-password')
  await user.click(screen.getByRole('button', { name: 'Change password' }))
  await waitFor(() => expect(getAccessToken()).toBe('changed-token'))
  await screen.findByRole('navigation', { name: 'Main navigation' })
})

it('discards a restore response after the session generation changes', async () => {
  let release: () => void = () => undefined
  let arrived: () => void = () => undefined
  const gate = new Promise<void>(resolve => { release = resolve })
  const started = new Promise<void>(resolve => { arrived = resolve })
  server.use(...sessionHandlers(pendingDeletionUser),
    http.get(`${API}/me`, () => HttpResponse.json({ deleteAfter: '2026-11-01T12:00:00Z' })),
    http.post(`${API}/me/restore`, async () => { arrived(); await gate; return HttpResponse.json(sessionFor(regularUser, 'late-restore')) }))
  renderApp('/app/profile')
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Current password (required)'), 'password')
  await user.click(screen.getByRole('button', { name: 'Restore' }))
  await started
  clearSession()
  release()
  await screen.findByRole('alert')
  expect(getAccessToken()).toBeNull()
  expect(screen.getByRole('heading', { name: 'Deletion scheduled' })).toBeInTheDocument()
})

it('preserves weather freshness and observation fields', async () => {
  const weather = { city: 'Ankara', temperature: 18, windSpeed: 4, weatherCode: 1, description: 'Cloudy', status: 'STALE' as const, observedAt: '2026-10-02T08:00:00Z' }
  server.use(http.get(`${API}/weather`, () => HttpResponse.json([weather])))
  expect(await fetchWeather()).toEqual([weather])
})
