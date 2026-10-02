import { QueryClient } from '@tanstack/react-query'
import { ApiError } from './api-error'

/** Per-resource freshness (milliseconds), used as `staleTime` by the feature hooks. */
export const STALE_TIME = {
  courses: 5 * 60_000,
  students: 30_000,
  accounts: 30_000,
  enrollments: 30_000,
  profile: 60_000,
  ipRules: 60_000,
  jobLogs: 15_000,
  weather: 10 * 60_000,
} as const

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // 4xx answers are final; one retry only for network failures and 5xx.
        retry: (failureCount, error) =>
          failureCount < 1 && (!(error instanceof ApiError) || error.status === 0 || error.status >= 500),
        refetchOnWindowFocus: false,
      },
      mutations: { retry: false },
    },
  })
}
