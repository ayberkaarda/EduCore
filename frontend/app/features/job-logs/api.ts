import { api } from '../../lib/api'
import type { JobLog, PageResponse } from '../../lib/types'
export const jobLogKeys = { all: ['job-logs'] as const }
export const MAX_IDS_PER_DELETE = 500
export interface JobLogFilters { status?: 'SUCCEEDED' | 'PARTIAL' | 'FAILED'; from?: string; to?: string; file?: string; page: number; size: number }
export interface JobLogEntry { id: number; rowNumber: number | null; level: 'INFO' | 'WARN' | 'ERROR'; reason: string; rawMasked: string | null }
export async function listJobLogs(params: JobLogFilters, signal?: AbortSignal) {
  return (await api.get<PageResponse<JobLog>>('/v1/admin/job-logs', { params, signal })).data
}
export async function listEntries(id: number, page: number, signal?: AbortSignal) {
  return (await api.get<PageResponse<JobLogEntry>>(`/v1/admin/job-logs/${id}/entries`, { params: { page, size: 50 }, signal })).data
}
export async function deleteJobLogs(ids: number[]): Promise<void> {
  for (let offset = 0; offset < ids.length; offset += MAX_IDS_PER_DELETE) await api.delete('/v1/admin/job-logs', { params: { ids: ids.slice(offset, offset + MAX_IDS_PER_DELETE).join(',') } })
}
