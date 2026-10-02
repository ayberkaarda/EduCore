import { api } from '../../lib/api'
import type { PageResponse } from '../../lib/types'
export const WEBHOOK_EVENTS = ['import.completed', 'import.failed', 'course.updated', 'account.deleted'] as const
export type WebhookEvent = typeof WEBHOOK_EVENTS[number]
export interface Webhook { id: number; url: string; events: WebhookEvent[]; active: boolean; createdBy: number; createdAt: string; updatedAt: string; droppedEvents: number }
export interface WebhookInput { url: string; events: WebhookEvent[]; active: boolean }
export interface WebhookDelivery { id: string; event: string; attempt: number; status: 'PENDING' | 'DELIVERED' | 'FAILED'; nextAttemptAt: string | null; responseCode: number | null; lastError: string | null; createdAt: string; deliveredAt: string | null }
export const webhookKeys = { all: ['webhooks'] as const }
export async function listWebhooks(signal?: AbortSignal) { return (await api.get<Webhook[]>('/v1/admin/webhooks', { signal })).data }
export async function createWebhook(body: WebhookInput) { return (await api.post<{ webhook: Webhook; secret: string }>('/v1/admin/webhooks', body)).data }
export async function updateWebhook(id: number, body: WebhookInput) { return (await api.put<Webhook>(`/v1/admin/webhooks/${id}`, body)).data }
export async function deleteWebhook(id: number) { await api.delete(`/v1/admin/webhooks/${id}`) }
export async function testWebhook(id: number) { return (await api.post<{ deliveryId: string }>(`/v1/admin/webhooks/${id}/test`)).data }
export async function listDeliveries(id: number, page: number, signal?: AbortSignal) { return (await api.get<PageResponse<WebhookDelivery>>(`/v1/admin/webhooks/${id}/deliveries`, { params: { page, size: 20 }, signal })).data }
