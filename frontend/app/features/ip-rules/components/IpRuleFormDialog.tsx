import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import Dialog from '../../../components/Dialog'
import TextField from '../../../components/TextField'
import { applyFieldErrors, describeApiError } from '../../../lib/error-messages'
import { denyRuleSchema, type DenyRuleValues } from '../schemas'
import type { DenyRuleInput, IpDenyRule } from '../api'
export default function IpRuleFormDialog({ rule, onSubmit, onCancel }: { rule?: IpDenyRule; onSubmit: (body: DenyRuleInput) => Promise<unknown>; onCancel: () => void }) {
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm<DenyRuleValues>({ resolver: zodResolver(denyRuleSchema), defaultValues: { kind: rule?.kind ?? 'STATIC', value: rule?.value ?? '', reason: rule?.reason ?? '', expiresAt: rule?.expiresAt ?? '' } })
  const submit = handleSubmit(async values => {
    try { await onSubmit({ ...values, expiresAt: values.expiresAt ? new Date(values.expiresAt).toISOString() : undefined }) }
    catch (error) { if (!applyFieldErrors(error, setError, ['kind', 'value', 'reason', 'expiresAt'])) setError('value', { message: describeApiError(error, 'Could not save the deny rule.') }) }
  })
  return <div className="modal-overlay"><Dialog className="modal-content"><h3>{rule ? 'Edit deny rule' : 'Add deny rule'}</h3><form onSubmit={submit} noValidate>
    <div className="form-group"><label htmlFor="deny-kind">Kind</label><select id="deny-kind" {...register('kind')} aria-invalid={!!errors.kind} aria-describedby={errors.kind ? 'deny-kind-error' : undefined}><option value="STATIC">Single address</option><option value="RANGE">Range</option><option value="CIDR">Subnet (CIDR)</option></select>{errors.kind && <p id="deny-kind-error" className="field-error">{errors.kind.message}</p>}</div>
    <TextField id="deny-value" label="Value (required)" registration={register('value')} error={errors.value?.message} hint="IPv4 address, start-end range or network/prefix." />
    <TextField id="deny-reason" label="Reason" registration={register('reason')} error={errors.reason?.message} />
    <TextField id="deny-expiry" label="Expiry" registration={register('expiresAt')} error={errors.expiresAt?.message} hint="ISO date-time with offset; leave empty for permanent." />
    <div className="modal-actions"><button type="button" className="btn-secondary" disabled={isSubmitting} onClick={onCancel}>Cancel</button><button className="btn-primary" disabled={isSubmitting}>Save rule</button></div>
  </form></Dialog></div>
}
