import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { STALE_TIME } from '../../lib/query-client'
import type { Role } from '../../lib/types'
import { studentKeys } from '../students/api'
import { accountKeys, changeRole, listAccounts, type AccountQuery } from './api'

export function useAccounts(query: AccountQuery) {
  return useQuery({
    queryKey: accountKeys.list(query),
    queryFn: ({ signal }) => listAccounts(query, signal),
    staleTime: STALE_TIME.accounts,
  })
}

export function useChangeRole() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ accountId, role }: { accountId: number; role: Role }) => changeRole(accountId, role),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: accountKeys.all })
      // A promoted student leaves the student listing (role USER only).
      await queryClient.invalidateQueries({ queryKey: studentKeys.all })
    },
  })
}
