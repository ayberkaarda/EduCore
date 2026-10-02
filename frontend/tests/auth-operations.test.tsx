import { QueryClientProvider } from '@tanstack/react-query'
import { useEffect } from 'react'
import { act, render, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { expect, it } from 'vitest'
import { AuthProvider } from '../app/features/auth/AuthProvider'
import { useAuth, type AuthContextValue } from '../app/features/auth/auth-context'
import { getAccessToken } from '../app/lib/api'
import { createQueryClient } from '../app/lib/query-client'
import { API, anonymousHandlers, regularUser, server, sessionFor } from './msw'

function Probe({ onValue }: { onValue: (value: AuthContextValue) => void }) {
  const value = useAuth()
  useEffect(() => { onValue(value) }, [onValue, value])
  return null
}

it('logout invalidates a pending login before its response can start a session', async () => {
  const auth: { current: AuthContextValue | null } = { current: null }
  const onValue = (value: AuthContextValue) => { auth.current = value }
  let release: () => void = () => undefined
  let arrived: () => void = () => undefined
  const gate = new Promise<void>(resolve => { release = resolve })
  const started = new Promise<void>(resolve => { arrived = resolve })
  server.use(...anonymousHandlers(),
    http.post(`${API}/auth/login`, async () => {
      arrived()
      await gate
      return HttpResponse.json(sessionFor(regularUser, 'late-login'))
    }),
    http.post(`${API}/auth/logout`, () => new HttpResponse(null, { status: 204 })),
  )
  render(<QueryClientProvider client={createQueryClient()}><AuthProvider><Probe onValue={onValue} /></AuthProvider></QueryClientProvider>)
  await waitFor(() => expect(auth.current?.status).toBe('anonymous'))
  const context = auth.current as unknown as AuthContextValue
  let result: Promise<unknown> = Promise.resolve()
  act(() => { result = context.login('ali', 'password').catch((error: unknown) => error) })
  await started
  let logout: Promise<void> = Promise.resolve()
  act(() => { logout = context.logout() })
  expect(getAccessToken()).toBeNull()
  release()
  await act(async () => { await result; await logout })
  expect(auth.current?.status).toBe('anonymous')
  expect(getAccessToken()).toBeNull()
})
