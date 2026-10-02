import { api } from '../../lib/api'
import type { IpAllocation, IpRuleType } from '../../lib/types'

export interface IpAllocationInput {
  type: IpRuleType
  originalValue: string
}

export const ipAllocationKeys = { all: ['ip-allocations'] as const }

export async function listIpAllocations(signal?: AbortSignal): Promise<IpAllocation[]> {
  const { data } = await api.get<IpAllocation[]>('/v1/admin/ip-allocations', { signal })
  return data
}

export async function createIpAllocation(body: IpAllocationInput): Promise<IpAllocation> {
  const { data } = await api.post<IpAllocation>('/v1/admin/ip-allocations', body)
  return data
}

export async function deleteIpAllocation(ipAllocationId: number): Promise<void> {
  await api.delete(`/v1/admin/ip-allocations/${encodeURIComponent(ipAllocationId)}`)
}
