import { useState } from 'react'
import axios from 'axios'
import toast, { Toaster } from 'react-hot-toast'
import { BookOpen } from 'lucide-react'

export default function Login({ setAuthData }) {
    const [credentials, setCredentials] = useState({ username: '', password: '' })

    const handleLogin = async (e) => {
        e.preventDefault()
        try {
            const response = await axios.post('http://localhost:8081/api/v1/auth/login', credentials)
            const { token, role, firstName } = response.data

            // Token'ı tarayıcıya kaydet
            localStorage.setItem('token', token)
            localStorage.setItem('role', role)
            localStorage.setItem('name', firstName)

            // Tüm Axios isteklerine otomatik Bearer Token ekle
            axios.defaults.headers.common['Authorization'] = `Bearer ${token}`

            setAuthData({ token, role, name: firstName })
            toast.success('Giriş başarılı!')
        } catch (error) {
            toast.error('Hatalı kullanıcı adı veya şifre!')
        }
    }

    return (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh', backgroundColor: '#f3f4f6' }}>
            <Toaster />
            <div className="card" style={{ width: '100%', maxWidth: '400px', textAlign: 'center' }}>
                <BookOpen size={48} color="#4f46e5" style={{ marginBottom: '1rem' }} />
                <h2 style={{ marginBottom: '2rem' }}>EduCore Login</h2>
                <form onSubmit={handleLogin} style={{ textAlign: 'left' }}>
                    <div className="form-group">
                        <label>Username</label>
                        <input required type="text" value={credentials.username} onChange={e => setCredentials({...credentials, username: e.target.value})} />
                    </div>
                    <div className="form-group" style={{ marginBottom: '2rem' }}>
                        <label>Password</label>
                        <input required type="password" value={credentials.password} onChange={e => setCredentials({...credentials, password: e.target.value})} />
                    </div>
                    <button type="submit" className="btn-primary" style={{ width: '100%' }}>Login to System</button>
                </form>
                <p style={{ marginTop: '1rem', fontSize: '0.8rem', color: '#6b7280' }}>
                    Admin Test: admin / 1234 <br/> User Test: ayberk / 1234
                </p>
            </div>
        </div>
    )
}