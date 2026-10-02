import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import Dialog from '../../../components/Dialog'
import TextField from '../../../components/TextField'
import { applyFieldErrors, describeApiError } from '../../../lib/error-messages'
import { WEBHOOK_EVENTS, type Webhook, type WebhookInput } from '../api'
import { webhookSchema, type WebhookValues } from '../schemas'
export default function WebhookFormDialog({ webhook, onSubmit, onCancel }: { webhook?: Webhook; onSubmit: (body: WebhookInput) => Promise<unknown>; onCancel: () => void }) {
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm<WebhookValues>({ resolver: zodResolver(webhookSchema), defaultValues: { url: webhook?.url ?? '', events: webhook?.events ?? [], active: webhook?.active ?? true } })
  const submit = handleSubmit(async body => {
    try { await onSubmit(body) } catch (error) { if (!applyFieldErrors(error, setError, ['url', 'events', 'active'])) setError('root', { message: describeApiError(error, 'Could not save the webhook.') }) }
  })
  return <div className="modal-overlay"><Dialog className="modal-content"><h3>{webhook ? 'Edit webhook' : 'Add webhook'}</h3><form onSubmit={submit} noValidate>
    <TextField id="webhook-url" label="URL (required)" registration={register('url')} error={errors.url?.message} />
    <fieldset aria-invalid={!!errors.events} aria-describedby={errors.events ? 'webhook-events-error' : undefined}><legend>Events (required)</legend>{WEBHOOK_EVENTS.map(event => <div key={event}><label><input type="checkbox" value={event} {...register('events')} /> {event}</label></div>)}</fieldset>{errors.events && <p id="webhook-events-error" className="field-error">{errors.events.message}</p>}
    <div className="form-group"><label><input type="checkbox" {...register('active')} /> Active</label></div>
    {errors.root && <p role="alert" className="field-error">{errors.root.message}</p>}
    <div className="modal-actions"><button type="button" className="btn-secondary" disabled={isSubmitting} onClick={onCancel}>Cancel</button><button className="btn-primary" disabled={isSubmitting}>Save webhook</button></div>
  </form></Dialog></div>
}
