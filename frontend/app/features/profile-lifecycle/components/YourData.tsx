import { zodResolver } from '@hookform/resolvers/zod'
import { useId, useState } from 'react'
import { useForm } from 'react-hook-form'
import Dialog from '../../../components/Dialog'
import { describeApiError } from '../../../lib/error-messages'
import { useAuth } from '../../auth/auth-context'
import { downloadExport, scheduleDeletion } from '../api'
import { deleteAccountSchema, type DeleteAccountValues } from '../schemas'
export default function YourData() {
  const [open, setOpen] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState('')
  const exportData = async () => {
    setExporting(true); setError('')
    try { await downloadExport() } catch (failure) { setError(describeApiError(failure, 'Could not export your data.')) }
    finally { setExporting(false) }
  }
  return <section className="card"><h3>Your data</h3><p>Download your profile, enrolments and security events as JSON.</p>
    {error && <p role="alert" className="inline-error">{error}</p>}
    <div className="modal-actions"><button type="button" className="btn-secondary" disabled={exporting} onClick={() => void exportData()}>Export</button>
    <button type="button" className="btn-secondary" onClick={() => setOpen(true)}>Delete my account</button></div>
    {open && <DeleteAccountDialog onCancel={() => setOpen(false)} />}
  </section>
}
function DeleteAccountDialog({ onCancel }: { onCancel: () => void }) {
  const { deletionScheduled } = useAuth()
  const id = useId()
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm<DeleteAccountValues>({ resolver: zodResolver(deleteAccountSchema), defaultValues: { currentPassword: '' } })
  const submit = handleSubmit(async values => {
    try { const result = await scheduleDeletion(values); deletionScheduled(result.deleteAfter) }
    catch (failure) { setError('root', { message: describeApiError(failure, 'Could not schedule account deletion.') }) }
  })
  return <div className="modal-overlay"><Dialog className="modal-content"><h3>Delete my account</h3>
    <p>Your account will be scheduled for permanent deletion after a 30-day grace period. Sign in again during this period to restore it. After permanent deletion, your profile and enrolments cannot be recovered.</p>
    <form onSubmit={submit} noValidate><div className="form-group"><label htmlFor={id}>Current password (required)</label>
      <input id={id} type="password" autoComplete="current-password" aria-invalid={!!errors.currentPassword} aria-describedby={errors.currentPassword ? `${id}-error` : undefined} {...register('currentPassword')} />
      {errors.currentPassword && <p id={`${id}-error`} className="field-error">{errors.currentPassword.message}</p>}</div>
      {errors.root && <p role="alert" className="inline-error">{errors.root.message}</p>}
      <div className="modal-actions"><button type="button" className="btn-secondary" disabled={isSubmitting} onClick={onCancel}>Keep my account</button>
      <button type="submit" className="btn-primary btn-danger" disabled={isSubmitting}>Schedule deletion</button></div>
    </form></Dialog></div>
}
