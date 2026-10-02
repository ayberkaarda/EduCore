import axios, { AxiosHeaders, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { ApiError, toApiError } from './api-error'
import type { SessionPayload } from './types'

/**
 * API base URL. Every path in this app starts with `/v1/...` and is resolved against this base, which ends
 * in `/api`:
 *   - default `/api`: same origin; the Vite dev server (and nginx in production) proxies `/api` to the backend.
 *   - `VITE_API_BASE_URL=http://localhost:8081/api`: the browser calls the backend directly (CORS + credentials).
 */
export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/+$/, '')

/** The single axios instance. Cookies are sent only on the auth calls that need the refresh cookie. */
export const api = axios.create({
  baseURL: API_BASE_URL,
  headers: { Accept: 'application/json' },
  timeout: 30_000,
})

const AUTH_PATH = '/v1/auth'
/** Auth calls that run without an access token; a 401 from them never triggers a refresh. */
const SESSION_CALL = /\/v1\/auth\/(login|refresh|logout)$/

interface RetriableConfig extends InternalAxiosRequestConfig {
  authRetried?: boolean
  sessionGeneration?: number
}

// ---------------------------------------------------------------------------------------------------------------
// In-memory session. The access token lives only in this module (never in Web Storage); a page reload restores
// the session through the HttpOnly refresh cookie (see AuthProvider.bootstrap).
// ---------------------------------------------------------------------------------------------------------------
let accessToken: string | null = null
let passwordChangeRequired = false
let sessionUserId: number | null = null
let sessionVersion = 0
let refreshInFlight: { generation: number; promise: Promise<SessionPayload> } | null = null
let sharedRefresh: { payload: SessionPayload; expiresAt: number } | null = null
const channel = typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined'
  ? new BroadcastChannel('educore-session') : null
if (channel) channel.onmessage = event => {
  const message = event.data as { type?: string; payload?: SessionPayload; expiresAt?: number; previousToken?: string | null }
  if (message.type === 'ended' && (accessToken === null || message.previousToken === accessToken)) endSession()
  else if (message.type === 'refreshed' && message.payload?.accessToken && typeof message.expiresAt === 'number'
    && (sessionUserId === null || message.payload.user.id === sessionUserId)
    && (accessToken === null || message.previousToken === accessToken || message.previousToken === sharedRefresh?.payload.accessToken)) {
    sharedRefresh = { payload: message.payload, expiresAt: message.expiresAt }
  }
}
let fallbackLock: Promise<unknown> = Promise.resolve()
export async function withSessionLock<T>(operation: () => Promise<T>): Promise<T> {
  // The DOM callback type is T itself; the async boundary flattens its Promise result.
  if (typeof navigator !== 'undefined' && navigator.locks?.request) return await navigator.locks.request<Promise<T>>('educore-refresh', operation)
  const result = fallbackLock.then(operation, operation)
  fallbackLock = result.catch(() => undefined)
  return result
}
export const getSessionGeneration = (): number => sessionVersion
export function assertSessionGeneration(generation: number): void {
  if (generation !== sessionVersion) throw new ApiError({ status: 401, code: 'auth/session-ended', title: 'The session has ended.' })
}
export function broadcastLogout(previousToken: string | null): void {
  channel?.postMessage({ type: 'ended', previousToken })
}
export function broadcastRefresh(payload: SessionPayload, previousToken: string | null): void {
  channel?.postMessage({ type: 'refreshed', payload, previousToken, expiresAt: Date.now() + payload.expiresIn * 1000 })
}

export interface SessionListener {
  /** A refresh replaced the access token; `payload.user` is the current server view of the user. */
  onRefreshed?: (payload: SessionPayload) => void
  /** The session could not be renewed (refresh failed, or a retried request was rejected again). */
  onExpired?: () => void
  onPendingDeletion?: () => void
  onPasswordChangeRequired?: () => void
}
let listener: SessionListener = {}

export function setSessionListener(next: SessionListener): () => void {
  listener = next
  return () => {
    if (listener === next) listener = {}
  }
}

export const getAccessToken = (): string | null => accessToken

/** Starts a new session (login, password change): later refreshes of an older session are discarded. */
export function startSession(payload: SessionPayload): void {
  if (!payload?.accessToken) throw new ApiError({ status: 0, code: 'auth/malformed-session', title: 'The server returned no access token.' })
  sessionVersion += 1
  accessToken = payload.accessToken
  passwordChangeRequired = payload.user.mustChangePassword && payload.user.status !== 'PENDING_DELETION'
  sessionUserId = payload.user.id
  sharedRefresh = null
}

/** Forgets the access token locally (sign-out or expiry). An in-flight refresh can no longer revive it. */
export function clearSession(): void {
  sessionVersion += 1
  accessToken = null
  passwordChangeRequired = false
  sessionUserId = null
  sharedRefresh = null
}

function endSession(): void {
  clearSession()
  listener.onExpired?.()
}

/**
 * Single-flight `POST /v1/auth/refresh`. Concurrent callers share one request: a second parallel use of the same
 * refresh cookie counts as token reuse on the server and revokes the whole session family.
 */
export function refreshSession(): Promise<SessionPayload> {
  const generation = sessionVersion
  if (refreshInFlight?.generation === generation) return refreshInFlight.promise
  const previousToken = accessToken
  const promise = withSessionLock(async () => {
    assertSessionGeneration(generation)
    // Allow broadcast messages queued while waiting for the lock to arrive.
    await new Promise<void>(resolve => setTimeout(resolve, 0))
    assertSessionGeneration(generation)
    const shared = sharedRefresh
    const data = shared && shared.expiresAt > Date.now() && shared.payload.accessToken !== previousToken
      ? shared.payload
      : (await api.post<SessionPayload>(`${AUTH_PATH}/refresh`, undefined, { withCredentials: true })).data
    assertSessionGeneration(generation)
    if (!data.accessToken) throw new ApiError({ status: 401, code: 'auth/malformed-session', title: 'The server returned no access token.' })
    accessToken = data.accessToken
    passwordChangeRequired = data.user.mustChangePassword && data.user.status !== 'PENDING_DELETION'
    sessionUserId = data.user.id
    sharedRefresh = { payload: data, expiresAt: Date.now() + data.expiresIn * 1000 }
    broadcastRefresh(data, previousToken)
    listener.onRefreshed?.(data)
    return data
  }).finally(() => {
    if (refreshInFlight?.promise === promise) refreshInFlight = null
  })
  refreshInFlight = { generation, promise }
  return promise
}

/** Auth calls (login, refresh, logout, password change) carry the refresh cookie; nothing else does. */
export const authCall = (config: AxiosRequestConfig = {}): AxiosRequestConfig => ({ ...config, withCredentials: true })

const bearerOf = (config: InternalAxiosRequestConfig): string | null => {
  const header = AxiosHeaders.from(config.headers).get('Authorization')
  return typeof header === 'string' && header.startsWith('Bearer ') ? header.slice('Bearer '.length) : null
}

api.interceptors.request.use(config => {
  const bound = config as RetriableConfig
  if (bound.sessionGeneration === undefined) bound.sessionGeneration = sessionVersion
  assertSessionGeneration(bound.sessionGeneration)
  if (passwordChangeRequired && !/^\/v1\/auth\/(me|password|refresh|logout)$/.test(config.url ?? '')) {
    throw new ApiError({ status: 403, code: 'account/password-change-required', title: 'Change your password to continue.' })
  }
  if (accessToken && !SESSION_CALL.test(config.url ?? '')) {
    config.headers.set('Authorization', `Bearer ${accessToken}`)
  }
  return config
})

api.interceptors.response.use(
  response => {
    assertSessionGeneration((response.config as RetriableConfig).sessionGeneration ?? sessionVersion)
    if (response.config.url === `${AUTH_PATH}/me` && response.data?.mustChangePassword === true) passwordChangeRequired = true
    return response
  },
  async (error: unknown) => {
    const apiError = toApiError(error)
    const config = (axios.isAxiosError(error) ? error.config : undefined) as RetriableConfig | undefined
    if (apiError.status === 403 && apiError.code === 'account/password-change-required') {
      if (config?.sessionGeneration === sessionVersion && accessToken && !passwordChangeRequired) {
        passwordChangeRequired = true
        listener.onPasswordChangeRequired?.()
      }
      throw apiError
    }
    if (apiError.status === 403 && apiError.code === 'account/pending-deletion') {
      if (config?.sessionGeneration === sessionVersion && accessToken) listener.onPendingDeletion?.()
      throw apiError
    }
    if (apiError.status !== 401 || !config || SESSION_CALL.test(config.url ?? '')) throw apiError
    if (config.sessionGeneration !== sessionVersion) throw apiError
    const generation = sessionVersion
    // Password changes already hold the session lock; refreshing here would deadlock.
    if (config.url === `${AUTH_PATH}/password`) {
      endSession()
      throw apiError
    }

    const sentToken = bearerOf(config)
    // Only requests that were sent with a token belong to a session that can be refreshed.
    if (!sentToken) throw apiError
    // The session already ended locally (sign-out or an earlier expiry): nothing to renew.
    if (!accessToken) throw apiError
    if (config.authRetried) {
      if (generation === sessionVersion) endSession()
      throw apiError
    }
    config.authRetried = true
    try {
      // Another request may already have refreshed the token while this one was in flight.
      if (sentToken === accessToken) await refreshSession()
    } catch {
      if (generation === sessionVersion) endSession()
      throw apiError
    }
    assertSessionGeneration(generation)
    config.headers.set('Authorization', `Bearer ${accessToken}`)
    return api.request(config)
  },
)
