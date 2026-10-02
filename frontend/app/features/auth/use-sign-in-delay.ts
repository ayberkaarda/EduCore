import { useEffect, useState } from 'react'
import { toApiError } from '../../lib/api-error'

export function useSignInDelay() {
  const [remaining, setRemaining] = useState(0)
  const [locked, setLocked] = useState(false)
  useEffect(() => {
    if (remaining <= 0) return
    const timer = setTimeout(() => setRemaining(seconds => Math.max(0, seconds - 1)), 1000)
    return () => clearTimeout(timer)
  }, [remaining])
  const delay = (error: unknown): boolean => {
    const problem = toApiError(error)
    if (problem.status !== 423 && problem.status !== 429) return false
    setLocked(problem.status === 423)
    setRemaining(Math.ceil(problem.retryAfterSeconds ?? 0))
    return problem.retryAfterSeconds !== undefined && problem.retryAfterSeconds > 0
  }
  return { remaining, delay, message: remaining > 0 ? `${locked ? 'The account is locked.' : 'Too many attempts.'} Try again in ${remaining} seconds.` : '' }
}
