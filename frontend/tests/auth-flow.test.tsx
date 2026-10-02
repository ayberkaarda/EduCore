import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAccessToken } from '../app/lib/api'
import { toast } from '../app/lib/toast'
import { API, adminUser, anonymousHandlers, problem, regularUser, server, sessionFor, sessionHandlers } from './msw'
import { renderApp } from './render-app'

const adminDashboard = () => [
  http.get(`${API}/admin/accounts/students`, () => HttpResponse.json({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 })),
  http.get(`${API}/courses`, () => HttpResponse.json([])),
]

describe('auth flow', () => {
  beforeEach(() => {
    vi.spyOn(Storage.prototype, 'setItem')
  })
  afterEach(() => {
    // Tokens, role and name never reach Web Storage (only the theme helper may write there).
    const writes = vi.mocked(Storage.prototype.setItem).mock.calls.filter(([key]) => key !== 'educore-theme')
    expect(writes).toEqual([])
  })

  it('bootstrap restores the session after a reload: one refresh, then /auth/me', async () => {
    const calls: string[] = []
    server.use(
      http.post(`${API}/auth/refresh`, () => {
        calls.push('refresh')
        return HttpResponse.json(sessionFor(adminUser))
      }),
      http.get(`${API}/auth/me`, ({ request }) => {
        calls.push(`me ${request.headers.get('Authorization')}`)
        return HttpResponse.json(adminUser)
      }),
      http.get(`${API}/weather`, () => HttpResponse.json([])),
      ...adminDashboard(),
    )

    renderApp('/app')

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(screen.getByText('Administrator')).toBeInTheDocument()
    // StrictMode mounts twice; the bootstrap is shared, so the refresh cookie is used exactly once.
    expect(calls.filter(call => call === 'refresh')).toHaveLength(1)
    expect(calls.indexOf('refresh')).toBeLessThan(calls.findIndex(call => call.startsWith('me')))
    expect(calls).toContain('me Bearer token-1')
  })

  it('without a refresh cookie the protected page sends the visitor to the sign-in screen', async () => {
    server.use(...anonymousHandlers())
    const { router } = renderApp('/app/courses')
    expect(await screen.findByRole('heading', { name: 'Sign in to EduCore' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/login')
  })

  it('signs in, keeps the token in memory and returns to the requested page', async () => {
    const user = userEvent.setup()
    let loginBody: unknown
    server.use(
      ...anonymousHandlers(),
      http.post(`${API}/auth/login`, async ({ request }) => {
        loginBody = await request.json()
        return HttpResponse.json(sessionFor(regularUser, 'login-token'))
      }),
      http.get(`${API}/courses`, () => HttpResponse.json([{ id: 5, name: 'Algorithms', term: '2026 Fall', instructor: null }])),
    )
    const { router } = renderApp('/app/courses')

    await user.type(await screen.findByLabelText('Username (required)'), 'ali')
    await user.type(screen.getByLabelText('Password (required)'), 'correct horse battery')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('cell', { name: 'Algorithms' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/courses')
    expect(loginBody).toEqual({ username: 'ali', password: 'correct horse battery' })
    expect(getAccessToken()).toBe('login-token')
  })

  it('shows the problem title mapping when the credentials are wrong', async () => {
    const user = userEvent.setup()
    server.use(...anonymousHandlers(), http.post(`${API}/auth/login`, () => problem(401, 'auth/invalid-credentials')))
    renderApp('/app/login')

    await user.type(await screen.findByLabelText('Username (required)'), 'ali')
    await user.type(screen.getByLabelText('Password (required)'), 'wrong')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    const alerts = await screen.findAllByText('The username or password is incorrect.')
    expect(alerts.length).toBeGreaterThan(0)
    expect(getAccessToken()).toBeNull()
  })

  it('a user with a temporary password is held on the change-password screen', async () => {
    server.use(...sessionHandlers({ ...regularUser, mustChangePassword: true }))
    const { router } = renderApp('/app/profile')
    expect(await screen.findByRole('heading', { name: 'Change your temporary password' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/change-password')
  })

  it('signing out clears the in-memory session and calls /auth/logout', async () => {
    const user = userEvent.setup()
    let logoutCalls = 0
    server.use(
      // The first matching handler wins, so this one precedes the default logout handler.
      http.post(`${API}/auth/logout`, () => {
        logoutCalls += 1
        return new HttpResponse(null, { status: 204 })
      }),
      ...sessionHandlers(adminUser),
      ...adminDashboard(),
    )
    const { router } = renderApp('/app')
    await user.click(await screen.findByRole('button', { name: 'Sign out' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/app/login'))
    expect(logoutCalls).toBe(1)
    expect(getAccessToken()).toBeNull()
  })

  it('old paths redirect to their /app equivalents', async () => {
    server.use(...sessionHandlers(adminUser), http.get(`${API}/admin/ip-allocations`, () => HttpResponse.json([])))
    const { router } = renderApp('/ips')
    expect(await screen.findByRole('heading', { name: 'IP allocations' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/ip-allocations')
  })
})

it('clears local auth before a delayed failed logout and offers a persistent retry', async () => {
  const user = userEvent.setup()
  let release: () => void = () => undefined
  const gate = new Promise<void>(resolve => { release = resolve })
  let calls = 0
  server.use(http.post(`${API}/auth/logout`, async () => {
    calls += 1
    if (calls === 1) {
      await gate
      return problem(503, 'server/internal-error')
    }
    return new HttpResponse(null, { status: 204 })
  }), ...sessionHandlers(adminUser), ...adminDashboard())
  const { router } = renderApp('/app')
  await user.click(await screen.findByRole('button', { name: 'Sign out' }))
  expect(getAccessToken()).toBeNull()
  await waitFor(() => expect(router.state.location.pathname).toBe('/app/login'))
  release()
  await screen.findByRole('button', { name: 'Retry' })
  act(() => {
    toast.info('One')
    toast.info('Two')
    toast.info('Three')
  })
  await user.click(screen.getByRole('button', { name: 'Retry' }))
  await waitFor(() => expect(calls).toBe(2))
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument())
})
