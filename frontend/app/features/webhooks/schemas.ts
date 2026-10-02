import { z } from '../../lib/zod'
import { WEBHOOK_EVENTS } from './api'
export const webhookSchema = z.object({
  url: z.string().trim().max(2048).refine(value => {
    try { const url = new URL(value); return url.protocol === 'https:' && !!url.hostname && !url.username && !url.password && !url.hash && !value.includes('#') }
    catch { return false }
  }, 'Enter an absolute HTTPS URL without credentials or a fragment.'),
  events: z.array(z.enum(WEBHOOK_EVENTS)).min(1, 'Select at least one event.').max(4).refine(events => new Set(events).size === events.length, 'Select each event only once.'),
  active: z.boolean(),
})
export type WebhookValues = z.infer<typeof webhookSchema>
