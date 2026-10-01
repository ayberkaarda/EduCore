import { api } from './api'
import { BrowserRouter as Router, Routes, Route, Link, NavLink, Navigate, useLocation } from 'react-router-dom'
import { useState, useEffect, useCallback, useRef } from 'react'
import { BookOpen, LogOut, Lock, LayoutGrid, Users, ScrollText, Network, Menu } from 'lucide-react'
import { LogoMark } from './Brand'
import ThemeToggle from './ThemeToggle'
import axios from 'axios'
import StudentDetail from './StudentDetail'
import StudentProfile from './StudentProfile'
import UserManagement from './UserManagement'
import StudentList from './StudentList'
import CourseManagement from './CourseManagement'
import JobLogs from './JobLogs'
import Login from './Login'
import Home from './Home'
import IpManagement from './IpManagement'
import WeatherWidget from './WeatherWidget'
import toast from 'react-hot-toast'
import Toaster from './Toasts'

const authClient = axios.create({ baseURL: api.auth, withCredentials: true })

axios.interceptors.request.use(config => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
}, error => {
  return Promise.reject(error);
});

const Unauthorized = () => <main className="auth-page"><section className="auth-card"><Lock size={24}/><h2>You do not have access to this page</h2><p className="text-gray">Sign in with an account that has access to continue.</p><Link to="/login" className="btn-primary">Sign in</Link></section></main>;

const ProtectedRoute = ({ authData, children }) => {
  const location = useLocation();
  if (!authData.token) {
    if (location.pathname === '/') {
      return <Navigate to="/login" replace />;
    }
    return <Navigate to="/unauthorized" replace />;
  }
  return children;
};

// Remove only the authentication keys written by Login.jsx (keeps preferences such as the theme).
const clearAuthStorage = () => ['token', 'role', 'name'].forEach(key => localStorage.removeItem(key))

const AppLayout = ({ authData, handleLogout, children }) => {
  const [menuOpen, setMenuOpen] = useState(false)
  const location = useLocation()
  const labels = { '/': 'Dashboard', '/students': 'Students', '/courses': 'Courses', '/logs': 'Job logs', '/ips': 'IP rules', '/profile': 'My profile', '/users': 'Users' }
  const navItem = (to, Icon, label) => <li><NavLink to={to} end={to === '/'} onClick={() => setMenuOpen(false)} title={label}><Icon size={20}/><span>{label}</span></NavLink></li>
  return <div className={menuOpen ? 'app-layout menu-open' : 'app-layout'}>
    <Toaster/>
    <a className="skip-link" href="#main-content">Skip to content</a>
    <nav className="sidebar" aria-label="Main navigation">
      <Link to="/" className="brand" aria-label="EduCore home"><span className="sidebar-lockup"><LogoMark/><span>EduCore</span></span></Link>
      <p className="nav-group">Registry</p><ul className="nav-links">{navItem('/', LayoutGrid, 'Dashboard')}{authData.role === 'ADMIN' && navItem('/students', Users, 'Students')}{navItem('/profile', Users, 'My profile')}{navItem('/courses', BookOpen, 'Courses')}</ul>
      {authData.role === 'ADMIN' && <><p className="nav-group">Administration</p><ul className="nav-links">{navItem('/users', Users, 'Users')}{navItem('/logs', ScrollText, 'Job logs')}{navItem('/ips', Network, 'IP rules')}</ul></>}
      <div className="sidebar-footer"><div className="user-profile"><span className="avatar">{authData.name?.slice(0,2).toUpperCase()}</span><div><p>{authData.name}</p><span className="badge neutral sidebar-badge">{authData.role === 'ADMIN' ? 'Administrator' : 'User'}</span></div></div><button onClick={handleLogout} className="sign-out" title="Sign out"><LogOut size={16}/><span>Sign out</span></button></div>
    </nav>
    <main className="content-area" id="main-content"><header className="top-bar"><button className="menu-toggle btn-secondary" aria-label="Toggle navigation" aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}><Menu size={20}/></button><span className="breadcrumb">{labels[location.pathname] || 'Students / Details'}</span><div className="top-tools"><WeatherWidget/><ThemeToggle/></div></header><div className="page-content">{children}</div></main>
  </div>
};

function App() {
  const [authData, setAuthData] = useState({
    token: localStorage.getItem('token'),
    role: localStorage.getItem('role'),
    name: localStorage.getItem('name'),
    mustChangePassword: false
  })
  const sessionVersion = useRef(0)
  const refreshPromise = useRef(null)

  const handleLogout = useCallback(async () => {
    sessionVersion.current += 1
    try {
      await authClient.post('/logout')
    } catch {
      // Clear the local session even when the server is unavailable.
    }
    clearAuthStorage()
    delete axios.defaults.headers.common['Authorization']
    toast.dismiss('must-change-password')
    setAuthData({ token: null, role: null, name: null, mustChangePassword: false })
  }, [])

  useEffect(() => {
    const interceptor = axios.interceptors.response.use(
        (response) => response,
        async (error) => {
          if (error.response?.status !== 401) return Promise.reject(error)
          const original = error.config
          const isAuthRequest = /\/auth\/(login|refresh|logout)(?:[/?#]|$)/.test(original?.url || '')
          if (!original || original._authRetried || isAuthRequest || !localStorage.getItem('token')) {
            await handleLogout()
            return Promise.reject(error)
          }
          original._authRetried = true
          const version = sessionVersion.current
          try {
            const currentToken = localStorage.getItem('token')
            if (original.headers.Authorization === `Bearer ${currentToken}`) {
              if (!refreshPromise.current) {
                refreshPromise.current = authClient.post('/refresh').then(({ data }) => {
                  if (!data.accessToken || version !== sessionVersion.current) throw error
                  localStorage.setItem('token', data.accessToken)
                  axios.defaults.headers.common['Authorization'] = `Bearer ${data.accessToken}`
                  setAuthData(previous => ({ ...previous, token: data.accessToken }))
                  return data.accessToken
                }).finally(() => {
                  refreshPromise.current = null
                })
              }
              await refreshPromise.current
            }
          } catch {
            await handleLogout()
            return Promise.reject(error)
          }
          if (version !== sessionVersion.current) return Promise.reject(error)
          original.headers.Authorization = `Bearer ${localStorage.getItem('token')}`
          return axios(original)
        }
    )

    return () => axios.interceptors.response.eject(interceptor)
  }, [handleLogout])

  return (
      <Router>
        <Routes>
          <Route path="/login" element={authData.token ? <Navigate to="/" replace /> : <Login setAuthData={setAuthData} />} />
          <Route path="/unauthorized" element={authData.token ? <Navigate to="/" replace /> : <Unauthorized />} />

          <Route path="/*" element={
            <ProtectedRoute authData={authData}>
              <AppLayout authData={authData} handleLogout={handleLogout}>
                <Routes>
                  <Route path="/" element={authData.role === 'ADMIN' ? <Home authData={authData} /> : <StudentProfile currentUser={authData} />} />
                  <Route path="/students" element={authData.role === 'ADMIN' ? <StudentList appMode={{ role: authData.role }} /> : <Navigate to="/profile" replace />} />
                  <Route path="/students/:id" element={authData.role === 'ADMIN' ? <StudentDetail /> : <Navigate to="/profile" replace />} />
                  <Route path="/profile" element={<StudentProfile currentUser={authData} />} />
                  <Route path="/users" element={authData.role === 'ADMIN' ? <UserManagement /> : <Navigate to="/profile" replace />} />
                  <Route path="/courses" element={<CourseManagement appMode={{ role: authData.role }} />} />
                  <Route path="/ips" element={authData.role === 'ADMIN' ? <IpManagement appMode={{ role: authData.role }} /> : <Navigate to="/profile" replace />} />
                  <Route path="/logs" element={authData.role === 'ADMIN' ? <JobLogs appMode={{ role: authData.role }} /> : <Navigate to="/profile" replace />} />
                  <Route path="*" element={<Navigate to="/" replace />} />
                </Routes>
              </AppLayout>
            </ProtectedRoute>
          } />
        </Routes>
      </Router>
  )
}

export default App
