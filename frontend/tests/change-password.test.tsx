import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { getAccessToken } from '../app/lib/api'
import { API, problem, regularUser, server, sessionFor, sessionHandlers } from './msw'
import { renderApp } from './render-app'

const temporaryUser = { ...regularUser, mustChangePassword: true }

async function fill(current: string, next: string, repeat = next) {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Current password (required)'), current)
  await user.type(screen.getByLabelText('New password (required)'), next)
  await user.type(screen.getByLabelText('Repeat new password (required)'), repeat)
  await user.click(screen.getByRole('button', { name: 'Change password' }))
  return user
}

describe('change-password screen', () => {
  it('lists every policy violation the server reports', async () => {
    server.use(
      ...sessionHandlers(temporaryUser),
      http.post(`${API}/auth/password`, () => problem(400, 'auth/password-policy', { violations: ['common_password', 'too_many_bytes'] })),
    )
    renderApp('/app/change-password')

    await fill('Temporary-Password-24chars', 'passwordREMOVED-DB-PASSWORD')

    const alert = await screen.findByText('The new password was rejected:')
    const list = within(alert.parentElement as HTMLElement).getByRole('list')
    expect(within(list).getByText('This password is too common. Choose a less predictable one.')).toBeInTheDocument()
    expect(within(list).getByText(/too long when encoded/)).toBeInTheDocument()
  })

  it('checks length, match and same-as-current locally before calling the API', async () => {
    let calls = 0
    server.use(
      ...sessionHandlers(temporaryUser),
      http.post(`${API}/auth/password`, () => {
        calls += 1
        return HttpResponse.json({})
      }),
    )
    renderApp('/app/change-password')

    await fill('Temporary-Password-24chars', 'short', 'different')

    expect(await screen.findByText('Use at least 12 characters.')).toBeInTheDocument()
    expect(screen.getByText('The passwords do not match.')).toBeInTheDocument()
    expect(calls).toBe(0)
  })

  it('reports a wrong current password on its field', async () => {
    server.use(...sessionHandlers(temporaryUser), http.post(`${API}/auth/password`, () => problem(400, 'auth/invalid-current-password')))
    renderApp('/app/change-password')

    await fill('wrong-current', 'a-much-better-passphrase')

    expect(await screen.findByText('The current password is incorrect.')).toBeInTheDocument()
    expect(screen.getByLabelText('Current password (required)')).toHaveAttribute('aria-invalid', 'true')
  })

  it('on success stores the new access token in memory and opens the app', async () => {
    let body: unknown
    server.use(
      ...sessionHandlers(temporaryUser),
      http.post(`${API}/auth/password`, async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(sessionFor(regularUser, 'after-change'))
      }),
      http.get(`${API}/me`, () => HttpResponse.json({ id: 2, username: 'ali', firstName: 'Ali', lastName: 'Veli', studentNumber: '2601001', role: 'USER', status: 'ACTIVE', deleteAfter: null })),
      http.get(`${API}/me/enrollments`, () => HttpResponse.json([])),
      http.get(`${API}/courses`, () => HttpResponse.json([])),
    )
    const { router } = renderApp('/app/change-password')

    await fill('Temporary-Password-24chars', 'a-much-better-passphrase')

    expect(await screen.findByRole('heading', { name: 'My profile' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app')
    expect(body).toEqual({ currentPassword: 'Temporary-Password-24chars', newPassword: 'a-much-better-passphrase' })
    expect(getAccessToken()).toBe('after-change')
  })
})

it('sign-out immediately invalidates a pending mandatory password change', async () => {
  let release: () => void = () => undefined
  let arrived: () => void = () => undefined
  const gate = new Promise<void>(resolve => { release = resolve })
  const started = new Promise<void>(resolve => { arrived = resolve })
  let logoutCalls = 0
  server.use(
    http.post(`${API}/auth/logout`, () => {
      logoutCalls += 1
      return new HttpResponse(null, { status: 204 })
    }),
    ...sessionHandlers(temporaryUser),
    http.post(`${API}/auth/password`, async () => {
      arrived()
      await gate
      return HttpResponse.json(sessionFor(regularUser, 'late-password-token'))
    }),
  )
  const { router } = renderApp('/app/change-password')
  const user = await fill('Temporary-Password-24chars', 'a-much-better-passphrase')
  await started
  await user.click(screen.getByRole('button', { name: 'Sign out' }))
  expect(getAccessToken()).toBeNull()
  await waitFor(() => expect(router.state.location.pathname).toBe('/app/login'))
  release()
  await waitFor(() => expect(logoutCalls).toBe(1))
  expect(getAccessToken()).toBeNull()
  expect(router.state.location.pathname).toBe('/app/login')
})
