import { createContext, useContext } from 'react'
import type { UserView } from '../../lib/types'

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

export interface AuthContextValue {
  status: AuthStatus
  user: UserView | null
  /** Restores the session from the refresh cookie: POST /auth/refresh, then GET /auth/me. */
  bootstrap: () => Promise<void>
  login: (username: string, password: string) => Promise<UserView>
  logout: () => Promise<void>
  restoreAccount: (currentPassword: string) => Promise<void>
  deletionScheduled: (deleteAfter: string) => void
  dismissDeletionConfirmation: () => void
  changePassword: (currentPassword: string, newPassword: string) => Promise<UserView>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>.')
  return value
}
