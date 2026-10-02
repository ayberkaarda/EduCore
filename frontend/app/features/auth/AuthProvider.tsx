import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { assertSessionGeneration, broadcastLogout, getSessionGeneration, clearSession, getAccessToken, refreshSession, setSessionListener, startSession } from '../../lib/api'
import { toast } from '../../lib/toast'
import { formatAccountDate } from '../../lib/date-format'
import type { UserView } from '../../lib/types'
import { ApiError } from '../../lib/api-error'
import DeletionScreen from '../profile-lifecycle/components/DeletionScreen'
import ChangePasswordScreen from './components/ChangePasswordScreen'
import LoginScreen from './components/LoginScreen'
import { Navigate } from 'react-router'
import { restoreMyAccount } from '../profile-lifecycle/api'
import { changePasswordRequest, fetchCurrentUser, loginRequest, logoutRequest } from './api'
import { AuthContext, type AuthContextValue, type AuthStatus } from './auth-context'

interface AuthState {
  status: AuthStatus
  user: UserView | null
  pendingDeletion?: boolean
  deleteAfter?: string
}

const SESSION_ENDED_TOAST = 'session-ended'

/**
 * Holds the signed-in user in memory only. Nothing about the session is written to Web Storage: after a reload
 * bootstrap() asks the server for a new access token with the HttpOnly refresh cookie.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<AuthState>({ status: 'loading', user: null })
  const bootstrapPromise = useRef<Promise<void> | null>(null)
  const statusRef = useRef<AuthStatus>('loading')
  useLayoutEffect(() => {
    statusRef.current = state.status
  }, [state.status])

  const becomeAnonymous = useCallback(() => {
    clearSession()
    queryClient.clear()
    setState({ status: 'anonymous', user: null })
  }, [queryClient])

  // Layout effect: the listener must exist before the first page requests start (passive effects).
  useLayoutEffect(() => setSessionListener({
    onRefreshed: payload => setState(previous => (previous.status === 'authenticated' ? { status: 'authenticated', user: payload.user } : previous)),
    onPasswordChangeRequired: () => {
      void queryClient.cancelQueries()
      queryClient.clear()
      setState(previous => ({ ...previous, user: previous.user ? { ...previous.user, mustChangePassword: true } : null }))
    },
    onPendingDeletion: () => setState(previous => ({
      ...previous,
      user: previous.user ? { ...previous.user, status: 'PENDING_DELETION' } : null,
      pendingDeletion: true,
    })),
    onExpired: () => {
      if (statusRef.current === 'authenticated') toast.info('Your session has ended. Sign in again.', { id: SESSION_ENDED_TOAST })
      queryClient.clear()
      setState({ status: 'anonymous', user: null })
    },
  }), [queryClient])

  const bootstrap = useCallback((): Promise<void> => {
    // StrictMode runs effects twice; both runs share one bootstrap (and therefore one refresh request).
    if (!bootstrapPromise.current) {
      const generation = getSessionGeneration()
      bootstrapPromise.current = (async () => {
        let refreshedUser: UserView | null = null
        try {
          if (!getAccessToken()) {
            const payload = await refreshSession()
            refreshedUser = payload.user
            if (payload.user.status === 'PENDING_DELETION') {
              assertSessionGeneration(generation)
              setState({ status: 'authenticated', user: payload.user, pendingDeletion: true })
              return
            }
          }
          const user = await fetchCurrentUser()
          assertSessionGeneration(generation)
          setState({ status: 'authenticated', user })
        } catch (error) {
          if (generation === getSessionGeneration()) {
            if (error instanceof ApiError && error.code === 'account/password-change-required' && refreshedUser) {
              setState({ status: 'authenticated', user: { ...refreshedUser, mustChangePassword: true } })
            } else if (error instanceof ApiError && error.code === 'account/pending-deletion') {
              setState(previous => {
                const user = refreshedUser ?? previous.user
                return {
                  status: 'authenticated',
                  user: user ? { ...user, status: 'PENDING_DELETION' } : null,
                  pendingDeletion: true,
                }
              })
            } else becomeAnonymous()
          }
        } finally {
          bootstrapPromise.current = null
        }
      })()
    }
    return bootstrapPromise.current
  }, [becomeAnonymous])
  const restoreAccount = useCallback(async (currentPassword: string) => {
    const generation = getSessionGeneration()
    const payload = await restoreMyAccount(currentPassword)
    assertSessionGeneration(generation)
    await queryClient.cancelQueries()
    assertSessionGeneration(generation)
    startSession(payload)
    queryClient.clear()
    setState({ status: 'authenticated', user: payload.user })
  }, [queryClient])

  useEffect(() => {
    void bootstrap()
  }, [bootstrap])

  const login = useCallback(async (username: string, password: string) => {
    clearSession()
    const generation = getSessionGeneration()
    const payload = await loginRequest(username, password)
    assertSessionGeneration(generation)
    startSession(payload)
    queryClient.clear()
    toast.dismiss(SESSION_ENDED_TOAST)
    setState({ status: 'authenticated', user: payload.user })
    return payload.user
  }, [queryClient])

  const logout = useCallback(async () => {
    const previousToken = getAccessToken()
    becomeAnonymous()
    broadcastLogout(previousToken)
    const generation = getSessionGeneration()
    const retry = async () => {
      if (generation !== getSessionGeneration()) return
      try {
        await logoutRequest()
        toast.dismiss('logout-failed')
      } catch {
        if (generation !== getSessionGeneration()) return
        toast.error('Sign-out could not reach the server. Retry to end the server session.', {
          id: 'logout-failed', duration: Infinity,
          action: { label: 'Retry', onClick: () => void retry() },
        })
      }
    }
    await retry()
  }, [becomeAnonymous])
  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    const generation = getSessionGeneration()
    const payload = await changePasswordRequest(currentPassword, newPassword)
    assertSessionGeneration(generation)
    startSession(payload)
    queryClient.clear()
    setState({ status: 'authenticated', user: payload.user })
    return payload.user
  }, [queryClient])

  const deletionScheduled = useCallback((deleteAfter: string) => {
    const previousToken = getAccessToken()
    becomeAnonymous()
    broadcastLogout(previousToken)
    setState({ status: 'anonymous', user: null, deleteAfter })
  }, [becomeAnonymous])
  const value = useMemo<AuthContextValue>(
    () => ({ status: state.status, user: state.user, bootstrap, login, logout, restoreAccount, changePassword, deletionScheduled, dismissDeletionConfirmation: becomeAnonymous }),
    [state, bootstrap, login, logout, restoreAccount, changePassword, deletionScheduled, becomeAnonymous],
  )
  return <AuthContext.Provider value={value}>{state.deleteAfter ? <><Navigate to="/app/login" replace /><LoginScreen returnTo="/app/courses" notice={`Deletion scheduled until ${formatAccountDate(state.deleteAfter)}; sign in to restore.`} /></> : state.user?.mustChangePassword && state.user.status !== 'PENDING_DELETION' ? <><Navigate to="/app/change-password" replace /><ChangePasswordScreen /></> : state.pendingDeletion || state.user?.status === 'PENDING_DELETION' ? <DeletionScreen /> : children}</AuthContext.Provider>
}
