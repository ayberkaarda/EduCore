import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { STALE_TIME } from '../../lib/query-client'
import { createIpAllocation, deleteIpAllocation, ipAllocationKeys, listIpAllocations, type IpAllocationInput } from './api'

export function useIpAllocations({ enabled = true }: { enabled?: boolean } = {}) {
  return useQuery({ queryKey: ipAllocationKeys.all, queryFn: ({ signal }) => listIpAllocations(signal), staleTime: STALE_TIME.ipRules, enabled })
}

export function useCreateIpAllocation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: IpAllocationInput) => createIpAllocation(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ipAllocationKeys.all }),
  })
}

export function useDeleteIpAllocation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteIpAllocation(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ipAllocationKeys.all }),
  })
}
