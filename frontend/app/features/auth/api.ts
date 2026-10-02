import { api, authCall, assertSessionGeneration, broadcastRefresh, getAccessToken, getSessionGeneration, withSessionLock } from '../../lib/api'
import type { Profile, SessionPayload, UserView } from '../../lib/types'

// Session endpoints (docs/api/ROUTES.md). Login, refresh, logout and password change carry the refresh cookie.

export async function loginRequest(username: string, password: string): Promise<SessionPayload> {
  const generation = getSessionGeneration()
  const { data } = await withSessionLock(async () => {
    assertSessionGeneration(generation)
    return api.post<SessionPayload>('/v1/auth/login', { username, password }, authCall())
  })
  return data
}

export async function logoutRequest(): Promise<void> {
  const generation = getSessionGeneration()
  await withSessionLock(async () => {
    assertSessionGeneration(generation)
    await api.post('/v1/auth/logout', undefined, authCall())
  })
}

export async function fetchCurrentUser(): Promise<UserView> {
  const { data } = await api.get<UserView>('/v1/auth/me')
  return data
}

export async function changePasswordRequest(currentPassword: string, newPassword: string): Promise<SessionPayload> {
  const generation = getSessionGeneration()
  const { data } = await withSessionLock(async () => {
    assertSessionGeneration(generation)
    const previousToken = getAccessToken()
    const response = await api.post<SessionPayload>('/v1/auth/password', { currentPassword, newPassword }, authCall())
    assertSessionGeneration(generation)
    broadcastRefresh(response.data, previousToken)
    return response
  })
  return data
}

export const profileKeys = { me: ['profile', 'me'] as const }

export async function fetchProfile(signal?: AbortSignal): Promise<Profile> {
  const { data } = await api.get<Profile>('/v1/me', { signal })
  return data
}
