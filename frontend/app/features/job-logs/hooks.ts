import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { deleteJobLogs, jobLogKeys, listJobLogs, listEntries, type JobLogFilters } from './api'
export function useJobLogs(filters: JobLogFilters) {
  return useQuery({ queryKey: [...jobLogKeys.all, filters], queryFn: ({ signal }) => listJobLogs(filters, signal) })
}
export function useJobEntries(id: number, page: number) {
  return useQuery({ queryKey: [...jobLogKeys.all, 'entries', id, page], queryFn: ({ signal }) => listEntries(id, page, signal) })
}
export function useDeleteJobLogs() {
  const client = useQueryClient()
  return useMutation({ mutationFn: deleteJobLogs, onSettled: () => client.invalidateQueries({ queryKey: jobLogKeys.all }) })
}
