import { http, HttpResponse } from 'msw'
import { expect, it, vi } from 'vitest'
import { API, adminUser, server, sessionFor } from './msw'

it('reuses the credentialed refresh broadcast received while waiting for the Web Lock', async () => {
  const descriptor = Object.getOwnPropertyDescriptor(navigator, 'locks')
  let receive: ((event: { data: unknown }) => void) | null = null
  const published: unknown[] = []
  class Channel {
    set onmessage(callback: (event: { data: unknown }) => void) { receive = callback }
    postMessage(message: unknown) { published.push(message) }
  }
  vi.stubGlobal('BroadcastChannel', Channel)
  const payload = sessionFor(adminUser, 'other-tab-token')
  const request = vi.fn(async (_name: string, operation: () => Promise<unknown>) => {
    receive?.({ data: { type: 'refreshed', payload, previousToken: 'old-token', expiresAt: Date.now() + 900_000 } })
    return operation()
  })
  Object.defineProperty(navigator, 'locks', { configurable: true, value: { request } })
  let refreshCalls = 0
  server.use(http.post(`${API}/auth/refresh`, () => {
    refreshCalls += 1
    return HttpResponse.json(sessionFor(adminUser))
  }))
  vi.resetModules()
  try {
    const client = await import('../app/lib/api')
    client.startSession(sessionFor(adminUser, 'old-token'))
    expect(await client.refreshSession()).toEqual(payload)
    expect(client.getAccessToken()).toBe('other-tab-token')
    expect(refreshCalls).toBe(0)
    expect(request).toHaveBeenCalledWith('educore-refresh', expect.any(Function))
    expect(published).toHaveLength(1)
    client.clearSession()
  } finally {
    if (descriptor) Object.defineProperty(navigator, 'locks', descriptor)
    else Reflect.deleteProperty(navigator, 'locks')
    vi.unstubAllGlobals()
    vi.resetModules()
  }
})
