import { zodResolver } from '@hookform/resolvers/zod'
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { FullLogo } from '../../../components/Brand'
import ThemeToggle from '../../../components/ThemeToggle'
import { toApiError } from '../../../lib/api-error'
import { applyFieldErrors, describeApiError } from '../../../lib/error-messages'
import { toast } from '../../../lib/toast'
import { useAuth } from '../auth-context'
import { changePasswordSchema, POLICY_MESSAGES, type ChangePasswordValues } from '../schemas'

const FIELDS = ['currentPassword', 'newPassword', 'confirmPassword'] as const

/** POST /api/v1/auth/password. Mandatory after signing in with a temporary password. */
export default function ChangePasswordScreen() {
  const { user, changePassword, logout } = useAuth()
  const navigate = useNavigate()
  const mandatory = Boolean(user?.mustChangePassword)
  const operation = useRef(0)
  useEffect(() => () => { operation.current += 1 }, [])
  const [policyViolations, setPolicyViolations] = useState<string[]>([])
  const [formError, setFormError] = useState('')
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm<ChangePasswordValues>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  })

  const submitValues = useCallback(async (values: ChangePasswordValues) => {
    const pending = ++operation.current
    setPolicyViolations([])
    setFormError('')
    try {
      await changePassword(values.currentPassword, values.newPassword)
      if (pending !== operation.current) return
      toast.success('Password changed.')
      navigate('/app', { replace: true })
    } catch (error) {
      if (pending !== operation.current) return
      const apiError = toApiError(error)
      if (apiError.code === 'auth/invalid-current-password') {
        setError('currentPassword', { type: 'server', message: describeApiError(apiError, 'The current password is incorrect.') })
      } else if (apiError.code === 'auth/password-policy') {
        setPolicyViolations(apiError.violations.length > 0 ? apiError.violations : ['unknown'])
      } else if (!applyFieldErrors(apiError, setError, FIELDS)) {
        setFormError(describeApiError(apiError, 'The password could not be changed.'))
      }
    }
  }, [changePassword, navigate, setError])

  const onSubmit = useCallback((event: FormEvent<HTMLFormElement>) => {
    void handleSubmit(submitValues)(event)
  }, [handleSubmit, submitValues])

  const describedBy = (field: (typeof FIELDS)[number]) => (errors[field] ? `${field}-error` : undefined)

  return (
    <main className="auth-page">
      <div className="auth-theme"><ThemeToggle /></div>
      <section className="auth-card">
        <FullLogo />
        <h2>{mandatory ? 'Change your temporary password' : 'Change password'}</h2>
        {mandatory && <p className="field-hint">You signed in with a temporary password. Choose your own password to continue.</p>}
        <form onSubmit={onSubmit} noValidate>
          <div className="form-group">
            <label htmlFor="currentPassword">Current password (required)</label>
            <input id="currentPassword" type="password" autoComplete="current-password"
              aria-invalid={errors.currentPassword ? true : undefined} aria-describedby={describedBy('currentPassword')} {...register('currentPassword')} />
            {errors.currentPassword && <p id="currentPassword-error" className="field-error">{errors.currentPassword.message}</p>}
          </div>
          <div className="form-group">
            <label htmlFor="newPassword">New password (required)</label>
            <input id="newPassword" type="password" autoComplete="new-password"
              aria-invalid={errors.newPassword ? true : undefined} aria-describedby={describedBy('newPassword') ?? 'newPassword-hint'} {...register('newPassword')} />
            <p id="newPassword-hint" className="field-hint">12 to 128 characters; common passwords are rejected.</p>
            {errors.newPassword && <p id="newPassword-error" className="field-error">{errors.newPassword.message}</p>}
          </div>
          <div className="form-group">
            <label htmlFor="confirmPassword">Repeat new password (required)</label>
            <input id="confirmPassword" type="password" autoComplete="new-password"
              aria-invalid={errors.confirmPassword ? true : undefined} aria-describedby={describedBy('confirmPassword')} {...register('confirmPassword')} />
            {errors.confirmPassword && <p id="confirmPassword-error" className="field-error">{errors.confirmPassword.message}</p>}
          </div>
          {policyViolations.length > 0 && (
            <div role="alert">
              <p className="inline-error">The new password was rejected:</p>
              <ul className="policy-list">
                {policyViolations.map(violation => (
                  <li key={violation}>{POLICY_MESSAGES[violation] ?? 'The password does not meet the password rules.'}</li>
                ))}
              </ul>
            </div>
          )}
          {formError && <p className="inline-error" role="alert">{formError}</p>}
          <button type="submit" className="btn-primary full-width" disabled={isSubmitting}>{isSubmitting ? 'Saving…' : 'Change password'}</button>
        </form>
        <p className="auth-caption">
          {mandatory
            ? <button type="button" className="btn-secondary" onClick={() => { operation.current += 1; void logout() }}>Sign out</button>
            : <Link to="/app">Back to EduCore</Link>}
        </p>
      </section>
    </main>
  )
}
