import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { hardDeleteAccount, restoreAccount, securityEvents } from './api'
export function useAccountLifecycle() {
  const client = useQueryClient()
  const onSuccess = async () => { await client.invalidateQueries() }
  const restore = useMutation({ mutationFn: restoreAccount, onSuccess })
  const purge = useMutation({ mutationFn: hardDeleteAccount, onSuccess })
  return { restore, purge }
}
export function useSecurityEvents(page: number) {
  return useQuery({ queryKey: ['security-events', page], queryFn: ({ signal }) => securityEvents(page, signal) })
}
