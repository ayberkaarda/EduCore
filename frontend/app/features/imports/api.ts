import { api } from '../../lib/api'
export interface ImportAccepted { inboxFileName: string; kind: 'STUDENTS' | 'COURSES'; rows: number; size: number; sha256: string }
export async function uploadImport(file: File) {
  const body = new FormData()
  body.append('file', file)
  return (await api.post<ImportAccepted>('/v1/admin/imports', body)).data
}
