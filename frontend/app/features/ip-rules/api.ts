import { api } from '../../lib/api'
import type { IpRuleType, PageResponse } from '../../lib/types'
export interface IpDenyRule {
  id: number; kind: IpRuleType; value: string; startIp: string; endIp: string
  reason: string | null; source: 'MANUAL' | 'AUTO'; expiresAt: string | null
  createdBy: number | null; createdAt: string
}
export interface DenyRuleInput { kind: IpRuleType; value: string; reason?: string; expiresAt?: string }
export const ipRuleKeys = { all: ['deny-rules'] as const }
export async function listIpRules(page: number, signal?: AbortSignal) {
  return (await api.get<PageResponse<IpDenyRule>>('/v1/admin/ip-rules', { params: { page, size: 20 }, signal })).data
}
export async function saveIpRule(body: DenyRuleInput, id?: number) {
  return id === undefined ? (await api.post<IpDenyRule>('/v1/admin/ip-rules', body)).data : (await api.put<IpDenyRule>(`/v1/admin/ip-rules/${id}`, body)).data
}
export async function deleteIpRule(id: number) { await api.delete(`/v1/admin/ip-rules/${id}`) }
