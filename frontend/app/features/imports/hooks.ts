import { useMutation, useQueryClient } from '@tanstack/react-query'
import { jobLogKeys } from '../job-logs/api'
import { uploadImport } from './api'
export function useUploadImport() {
  const client = useQueryClient()
  return useMutation({ mutationFn: uploadImport, onSuccess: () => client.invalidateQueries({ queryKey: jobLogKeys.all }) })
}
