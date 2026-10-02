import { api } from '../../lib/api'
import type { Account, PageResponse, Role } from '../../lib/types'

export interface AccountQuery {
  search: string
  page: number
  size: number
  deleted: boolean
}

/** Admin account listings; student writes invalidate this cache as well (same rows). */
export const accountKeys = {
  all: ['accounts'] as const,
  list: (query: AccountQuery) => ['accounts', 'list', query] as const,
}

export async function listAccounts(query: AccountQuery, signal?: AbortSignal): Promise<PageResponse<Account>> {
  const { data } = await api.get<PageResponse<Account>>('/v1/admin/accounts', { params: query, signal })
  return data
}

export async function changeRole(accountId: number, role: Role): Promise<Account> {
  const { data } = await api.put<Account>(`/v1/admin/accounts/${encodeURIComponent(accountId)}/role`, { role })
  return data
}
