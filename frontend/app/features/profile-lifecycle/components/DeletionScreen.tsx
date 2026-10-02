import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { toApiError } from '../../../lib/api-error'
import { deleteAccountSchema, type DeleteAccountValues } from '../schemas'
import { Link, useNavigate } from 'react-router'
import { FullLogo } from '../../../components/Brand'
import { describeApiError } from '../../../lib/error-messages'
import { fetchProfile, profileKeys } from '../../auth/api'
import { useAuth } from '../../auth/auth-context'
import { formatAccountDate } from '../../../lib/date-format'
export default function DeletionScreen({ deleteAfter }: { deleteAfter?: string }) {
  const { logout, restoreAccount, dismissDeletionConfirmation } = useAuth()
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const { register, handleSubmit, setError: setFieldError, formState: { errors } } = useForm<DeleteAccountValues>({ resolver: zodResolver(deleteAccountSchema), defaultValues: { currentPassword: '' } })
  const profile = useQuery({ queryKey: profileKeys.me, queryFn: ({ signal }) => fetchProfile(signal), enabled: !deleteAfter, staleTime: 0 })
  const date = deleteAfter ?? profile.data?.deleteAfter
  const restore = handleSubmit(async values => {
    setBusy(true); setError('')
    try {
      await restoreAccount(values.currentPassword)
      void navigate('/app/courses', { replace: true })
    } catch (failure) {
      const apiError = toApiError(failure)
      if (apiError.code === 'auth/invalid-current-password') setFieldError('currentPassword', { message: 'The current password is incorrect.' })
      else setError(describeApiError(apiError, 'Could not restore your account.'))
    }
    finally { setBusy(false) }
  })
  return <main className="auth-page"><section className="auth-card"><FullLogo /><h2>Deletion scheduled</h2>
    <p>{date ? <>Your account will be permanently deleted after <time dateTime={date}>{formatAccountDate(date)}</time>.</> : 'Loading your deletion date.'}</p>
    <p>You can restore your account during the 30-day grace period.</p>
    {(error || profile.error) && <p role="alert" className="inline-error">{error || describeApiError(profile.error, 'Could not load your deletion date.')}</p>}
    {profile.isError && <button type="button" className="btn-secondary" onClick={() => void profile.refetch()}>Retry</button>}
    {deleteAfter ? <Link to="/app/login" className="btn-primary" onClick={dismissDeletionConfirmation}>Sign in again</Link> : <form onSubmit={restore} noValidate>
      <div className="form-group"><label htmlFor="restore-password">Current password (required)</label>
      <input id="restore-password" type="password" autoComplete="current-password" aria-invalid={!!errors.currentPassword} aria-describedby={errors.currentPassword ? 'restore-password-error' : undefined} {...register('currentPassword')} />
      {errors.currentPassword && <p id="restore-password-error" className="field-error">{errors.currentPassword.message}</p>}</div><div className="modal-actions">
      <button type="submit" className="btn-primary" disabled={busy}>Restore</button>
      <button type="button" className="btn-secondary" disabled={busy} onClick={() => void logout()}>Sign out</button>
    </div></form>}
  </section></main>
}
