import { api } from '../../lib/api'
import type { Account, PageResponse, SessionPayload, SecurityEvent } from '../../lib/types'
import type { DeleteAccountValues } from './schemas'
export interface DeletionScheduled { status: 'PENDING_DELETION'; deleteAfter: string }
export async function scheduleDeletion(body: DeleteAccountValues) {
  return (await api.delete<DeletionScheduled>('/v1/me', { data: body })).data
}
export async function restoreMyAccount(currentPassword: string) { return (await api.post<SessionPayload>('/v1/me/restore', { currentPassword }, { withCredentials: true })).data }
export async function downloadExport() {
  // Parse JSON before making the download blob so problem codes and Retry-After remain available on errors.
  const { data } = await api.get<unknown>('/v1/me/export')
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `educore-export-${new Date().toISOString().slice(0, 10)}.json`
  document.body.append(anchor)
  try { anchor.click() } finally { anchor.remove(); URL.revokeObjectURL(url) }
}
export async function restoreAccount(id: number) { return (await api.post<Account>(`/v1/admin/accounts/${id}/restore`)).data }
export async function hardDeleteAccount(account: Account) {
  await api.post(`/v1/admin/accounts/${account.id}/purge`, { confirm: account.username })
}
export async function unlockLogin(id: number): Promise<void> { await api.post(`/v1/admin/accounts/${id}/unlock-login`) }
export async function securityEvents(page: number, signal?: AbortSignal) {
  return (await api.get<PageResponse<SecurityEvent>>('/v1/admin/security-events', { params: { page, size: 20 }, signal })).data
}
