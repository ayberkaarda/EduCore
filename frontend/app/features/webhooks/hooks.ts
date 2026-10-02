import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { deleteWebhook, updateWebhook, testWebhook, webhookKeys, listWebhooks, listDeliveries, type WebhookInput } from './api'
export function useWebhooks() { return useQuery({ queryKey: webhookKeys.all, queryFn: ({ signal }) => listWebhooks(signal) }) }
export function useDeliveries(id: number, page: number) { return useQuery({ queryKey: [...webhookKeys.all, 'deliveries', id, page], queryFn: ({ signal }) => listDeliveries(id, page, signal), refetchInterval: 5000 }) }
export function useUpdateWebhook() {
  const client = useQueryClient()
  return useMutation({ mutationFn: ({ id, body }: { id: number; body: WebhookInput }) => updateWebhook(id, body), onSuccess: () => client.invalidateQueries({ queryKey: webhookKeys.all }) })
}
export function useDeleteWebhook() {
  const client = useQueryClient()
  return useMutation({ mutationFn: deleteWebhook, onSuccess: () => client.invalidateQueries({ queryKey: webhookKeys.all }) })
}
export function useTestWebhook() {
  const client = useQueryClient()
  return useMutation({ mutationFn: testWebhook, onSuccess: () => client.invalidateQueries({ queryKey: webhookKeys.all }) })
}
