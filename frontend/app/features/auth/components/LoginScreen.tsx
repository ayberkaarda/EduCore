import { zodResolver } from '@hookform/resolvers/zod'
import { CircleX, Eye, EyeOff } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { FullLogo } from '../../../components/Brand'
import ThemeToggle from '../../../components/ThemeToggle'
import { describeApiError } from '../../../lib/error-messages'
import { toast } from '../../../lib/toast'
import { useAuth } from '../auth-context'
import { loginSchema, type LoginValues } from '../schemas'
import { useSignInDelay } from '../use-sign-in-delay'

export default function LoginScreen({ returnTo, notice }: { returnTo: string; notice?: string }) {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [showPassword, setShowPassword] = useState(false)
  const [loginError, setLoginError] = useState('')
  const throttle = useSignInDelay()
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { username: '', password: '' },
  })

  const onSubmit = handleSubmit(async values => {
    if (throttle.remaining > 0) return
    setLoginError('')
    try {
      const user = await login(values.username, values.password)
      if (user.status !== 'PENDING_DELETION') toast.success('Signed in.')
      navigate(user.status !== 'PENDING_DELETION' && user.mustChangePassword ? '/app/change-password' : returnTo, { replace: true })
    } catch (error) {
      if (throttle.delay(error)) return
      const message = describeApiError(error, 'The username or password is incorrect.')
      setLoginError(message)
      toast.error(message)
    }
  })

  return (
    <main className="auth-page">
      <div className="auth-theme"><ThemeToggle /></div>
      <section className="auth-card">
        <FullLogo />
        <h2>Sign in to EduCore</h2>
        {notice && <p role="status">{notice}</p>}
        <form onSubmit={onSubmit} noValidate>
          <div className="form-group">
            <label htmlFor="username">Username (required)</label>
            <input id="username" autoComplete="username" type="text" aria-invalid={errors.username ? true : undefined}
              aria-describedby={errors.username ? 'username-error' : undefined} {...register('username')} />
            {errors.username && <p id="username-error" className="field-error">{errors.username.message}</p>}
          </div>
          <div className="form-group">
            <label htmlFor="password">Password (required)</label>
            <div className="password-field">
              <input id="password" autoComplete="current-password" type={showPassword ? 'text' : 'password'}
                aria-invalid={errors.password ? true : undefined} aria-describedby={errors.password ? 'password-error' : undefined}
                {...register('password')} />
              <button type="button" className="password-toggle" aria-label={showPassword ? 'Hide password' : 'Show password'} onClick={() => setShowPassword(!showPassword)}>
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
            {errors.password && <p id="password-error" className="field-error">{errors.password.message}</p>}
          </div>
          {(throttle.message || loginError) && <p className="inline-error" role="alert"><CircleX size={16} />{throttle.message || loginError}</p>}
          <button type="submit" className="btn-primary full-width" disabled={isSubmitting || throttle.remaining > 0}>{isSubmitting ? 'Signing in…' : 'Sign in'}</button>
        </form>
        <p className="auth-caption">EduCore · Student and course registry</p>
      </section>
    </main>
  )
}
