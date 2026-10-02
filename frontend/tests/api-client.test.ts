import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, authCall, clearSession, getAccessToken, refreshSession, setSessionListener, startSession } from '../app/lib/api'
import { ApiError } from '../app/lib/api-error'
import { API, adminUser, problem, server, sessionFor, sessionHandlers, SESSION_CREDENTIAL_HEADER } from './msw'

let unregister: () => void = () => undefined
afterEach(() => unregister())

describe('api client: refresh on 401', () => {
  it('runs one refresh for parallel 401s and retries every request once with the new token', async () => {
    startSession(sessionFor(adminUser, 'expired-token'))
    const onRefreshed = vi.fn()
    unregister = setSessionListener({ onRefreshed })
    let refreshCalls = 0
    const authorizations: string[] = []
    server.use(
      http.post(`${API}/auth/refresh`, async () => {
        refreshCalls += 1
        await new Promise(resolve => setTimeout(resolve, 20))
        return HttpResponse.json(sessionFor(adminUser, 'fresh-token'))
      }),
      http.get(`${API}/courses`, ({ request }) => {
        const authorization = request.headers.get('Authorization') ?? ''
        authorizations.push(authorization)
        return authorization === 'Bearer fresh-token' ? HttpResponse.json([]) : problem(401, 'auth/unauthenticated')
      }),
    )

    const results = await Promise.all([1, 2, 3, 4].map(() => api.get('/v1/courses')))

    expect(results.map(result => result.status)).toEqual([200, 200, 200, 200])
    expect(refreshCalls).toBe(1)
    expect(authorizations.filter(value => value === 'Bearer expired-token')).toHaveLength(4)
    expect(authorizations.filter(value => value === 'Bearer fresh-token')).toHaveLength(4)
    expect(getAccessToken()).toBe('fresh-token')
    expect(onRefreshed).toHaveBeenCalledTimes(1)
  })

  it('ends the session when the refresh fails', async () => {
    startSession(sessionFor(adminUser, 'expired-token'))
    const onExpired = vi.fn()
    unregister = setSessionListener({ onExpired })
    server.use(
      http.post(`${API}/auth/refresh`, () => problem(401, 'auth/invalid-refresh-token')),
      http.get(`${API}/courses`, () => problem(401, 'auth/unauthenticated')),
    )

    const failure = await api.get('/v1/courses').catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiError)
    expect((failure as ApiError).code).toBe('auth/unauthenticated')
    expect(onExpired).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBeNull()
  })

  it('retries only once: a second 401 after a successful refresh ends the session', async () => {
    startSession(sessionFor(adminUser, 'expired-token'))
    const onExpired = vi.fn()
    unregister = setSessionListener({ onExpired })
    let courseCalls = 0
    server.use(
      http.post(`${API}/auth/refresh`, () => HttpResponse.json(sessionFor(adminUser, 'fresh-token'))),
      http.get(`${API}/courses`, () => {
        courseCalls += 1
        return problem(401, 'auth/unauthenticated')
      }),
    )

    await expect(api.get('/v1/courses')).rejects.toBeInstanceOf(ApiError)
    expect(courseCalls).toBe(2)
    expect(onExpired).toHaveBeenCalledTimes(1)
  })

  it('does not refresh for 401s of the session calls themselves', async () => {
    let refreshCalls = 0
    server.use(
      http.post(`${API}/auth/login`, () => problem(401, 'auth/invalid-credentials')),
      http.post(`${API}/auth/refresh`, () => {
        refreshCalls += 1
        return problem(401, 'auth/invalid-refresh-token')
      }),
    )
    const failure = await api.post('/v1/auth/login', { username: 'a', password: 'b' }).catch((error: unknown) => error)
    expect((failure as ApiError).code).toBe('auth/invalid-credentials')
    expect(refreshCalls).toBe(0)
  })

  it('turns other failures into ApiError without touching the session', async () => {
    startSession(sessionFor(adminUser, 'valid-token'))
    server.use(http.get(`${API}/admin/ip-rules`, () => problem(403, 'auth/access-denied')))
    const failure = await api.get('/v1/admin/ip-rules').catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(ApiError)
    expect((failure as ApiError).status).toBe(403)
    expect(getAccessToken()).toBe('valid-token')
  })
})

function deferred() {
  let release: () => void = () => undefined
  const promise = new Promise<void>(resolve => { release = resolve })
  return { promise, release }
}

describe('session generation races', () => {
  it('never replays a delayed 401 from A with B credentials', async () => {
    const arrived = deferred()
    const gate = deferred()
    let calls = 0
    startSession(sessionFor(adminUser, 'A'))
    server.use(http.get(`${API}/courses`, async () => {
      calls += 1
      arrived.release()
      await gate.promise
      return problem(401, 'auth/unauthenticated')
    }))
    const result = api.get('/v1/courses').catch((error: unknown) => error)
    await arrived.promise
    clearSession()
    startSession(sessionFor({ ...adminUser, id: 2 }, 'B'))
    gate.release()
    expect(await result).toBeInstanceOf(ApiError)
    expect(calls).toBe(1)
    expect(getAccessToken()).toBe('B')
  })

  it('a failed old refresh cannot expire B', async () => {
    const arrived = deferred()
    const gate = deferred()
    const onExpired = vi.fn()
    unregister = setSessionListener({ onExpired })
    startSession(sessionFor(adminUser, 'A'))
    server.use(
      http.get(`${API}/courses`, () => problem(401, 'auth/unauthenticated')),
      http.post(`${API}/auth/refresh`, async () => {
        arrived.release()
        await gate.promise
        return problem(401, 'auth/invalid-refresh-token')
      }),
    )
    const result = api.get('/v1/courses').catch((error: unknown) => error)
    await arrived.promise
    clearSession()
    startSession(sessionFor({ ...adminUser, id: 2 }, 'B'))
    gate.release()
    expect(await result).toBeInstanceOf(ApiError)
    expect(getAccessToken()).toBe('B')
    expect(onExpired).not.toHaveBeenCalled()
  })

  it('discards a successful refresh that finishes after logout', async () => {
    const arrived = deferred()
    const gate = deferred()
    server.use(http.post(`${API}/auth/refresh`, async () => {
      arrived.release()
      await gate.promise
      return HttpResponse.json(sessionFor(adminUser, 'late'))
    }))
    const result = refreshSession().catch((error: unknown) => error)
    await arrived.promise
    clearSession()
    gate.release()
    expect(await result).toBeInstanceOf(ApiError)
    expect(getAccessToken()).toBeNull()
  })

  it('uses the Web Lock and sends credentialed refresh requests', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(navigator, 'locks')
    const request = vi.fn(async (_name: string, callback: () => Promise<unknown>) => callback())
    Object.defineProperty(navigator, 'locks', { configurable: true, value: { request } })
    let credentialed = false
    const interceptor = api.interceptors.request.use(config => {
      if (config.url === '/v1/auth/refresh') credentialed = config.withCredentials === true
      return config
    })
    server.use(http.post(`${API}/auth/refresh`, () => HttpResponse.json(sessionFor(adminUser))))
    try {
      await refreshSession()
      expect(request).toHaveBeenCalledWith('educore-refresh', expect.any(Function))
      expect(credentialed).toBe(true)
      expect(authCall().withCredentials).toBe(true)
    } finally {
      api.interceptors.request.eject(interceptor)
      if (descriptor) Object.defineProperty(navigator, 'locks', descriptor)
      else Reflect.deleteProperty(navigator, 'locks')
    }
  })
})
it('session handlers reject missing credentials and a disallowed Origin', async () => {
  server.use(...sessionHandlers(adminUser))
  for (const path of ['refresh', 'logout']) {
    await expect(api.post(`/v1/auth/${path}`, undefined, { withCredentials: false })).rejects.toMatchObject({ status: 401 })
    const response = await fetch(`${API}/auth/${path}`, { method: 'POST', credentials: 'include', headers: { Origin: 'https://untrusted.example', [SESSION_CREDENTIAL_HEADER]: 'include' } })
    expect(response.status).toBe(403)
    expect(await response.json()).toMatchObject({ code: 'auth/origin-rejected' })
  }
  const interceptor = api.interceptors.request.use(config => {
    expect(config.withCredentials).toBe(true)
    return config
  })
  try {
    const allowed = await api.post('/v1/auth/refresh', undefined, authCall())
    expect(allowed.status).toBe(200)
    const explicitOrigin = await fetch(`${API}/auth/refresh`, {
      method: 'POST', credentials: 'include',
      headers: { Origin: 'http://localhost:3000', [SESSION_CREDENTIAL_HEADER]: 'include' },
    })
    expect(explicitOrigin.status).toBe(200)
  } finally {
    api.interceptors.request.eject(interceptor)
  }
})

it('a delayed logout response cannot change the newer local session', async () => {
  const arrived = deferred()
  const gate = deferred()
  startSession(sessionFor(adminUser, 'A'))
  server.use(http.post(`${API}/auth/logout`, async () => {
    arrived.release()
    await gate.promise
    return new HttpResponse(null, { status: 204 })
  }))
  clearSession()
  const result = api.post('/v1/auth/logout', undefined, authCall()).catch((error: unknown) => error)
  await arrived.promise
  startSession(sessionFor({ ...adminUser, id: 2 }, 'B'))
  gate.release()
  expect(await result).toBeInstanceOf(ApiError)
  expect(getAccessToken()).toBe('B')
})

it('a delayed retried 401 cannot expire a newer session', async () => {
  const arrived = deferred()
  const gate = deferred()
  const onExpired = vi.fn()
  unregister = setSessionListener({ onExpired })
  startSession(sessionFor(adminUser, 'expired'))
  server.use(
    http.post(`${API}/auth/refresh`, () => HttpResponse.json(sessionFor(adminUser, 'renewed'))),
    http.get(`${API}/courses`, async ({ request }) => {
      if (request.headers.get('Authorization') === 'Bearer renewed') {
        arrived.release()
        await gate.promise
      }
      return problem(401, 'auth/unauthenticated')
    }),
  )
  const result = api.get('/v1/courses').catch((error: unknown) => error)
  await arrived.promise
  clearSession()
  startSession(sessionFor({ ...adminUser, id: 2 }, 'B'))
  gate.release()
  expect(await result).toBeInstanceOf(ApiError)
  expect(getAccessToken()).toBe('B')
  expect(onExpired).not.toHaveBeenCalled()
})
