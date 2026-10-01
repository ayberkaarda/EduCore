import { api, apiError } from './api'
import { useState } from 'react'
import axios from 'axios'
import toast from 'react-hot-toast'
import Toaster from './Toasts'
import { Eye, EyeOff, CircleX } from 'lucide-react'
import { FullLogo } from './Brand'
import ThemeToggle from './ThemeToggle'

export default function Login({ setAuthData }) {
    const [showPassword, setShowPassword] = useState(false)
    const [loginError, setLoginError] = useState('')
    const [credentials, setCredentials] = useState({ username: '', password: '' })

    const handleLogin = async (e) => {
        e.preventDefault()
        try {
            const response = await axios.post(api.login, credentials, { withCredentials: true })
            const { accessToken: token, user: { role, firstName, mustChangePassword } } = response.data

            // Token'ı tarayıcıya kaydet
            localStorage.setItem('token', token)
            localStorage.setItem('role', role)
            localStorage.setItem('name', firstName)
            if (mustChangePassword) localStorage.setItem('mustChangePassword', 'true')
            else localStorage.removeItem('mustChangePassword')

            // Tüm Axios isteklerine otomatik Bearer Token ekle
            axios.defaults.headers.common['Authorization'] = `Bearer ${token}`

            setAuthData({ token, role, name: firstName, mustChangePassword: Boolean(mustChangePassword) })
            toast.success('Signed in.')
        } catch (error) {
            setLoginError(apiError(error, 'The username or password is incorrect.'))
            toast.error(apiError(error, 'The username or password is incorrect.'))
        }
    }

    return <main className="auth-page"><div className="auth-theme"><ThemeToggle/></div><Toaster/><section className="auth-card"><FullLogo/><h2>Sign in to EduCore</h2><form onSubmit={handleLogin}><div className="form-group"><label htmlFor="username">Username (required)</label><input id="username" autoComplete="username" required type="text" value={credentials.username} onChange={e => setCredentials({...credentials, username:e.target.value})}/></div><div className="form-group"><label htmlFor="password">Password (required)</label><div className="password-field"><input id="password" autoComplete="current-password" required type={showPassword ? 'text' : 'password'} value={credentials.password} onChange={e => setCredentials({...credentials, password:e.target.value})}/><button type="button" className="password-toggle" aria-label={showPassword ? 'Hide password' : 'Show password'} onClick={() => setShowPassword(!showPassword)}>{showPassword ? <EyeOff size={16}/> : <Eye size={16}/>}</button></div></div>{loginError && <p className="inline-error" role="alert"><CircleX size={16}/>{loginError}</p>}<button type="submit" className="btn-primary full-width">Sign in</button></form><p className="auth-caption">EduCore · Student and course registry</p></section></main>
}
