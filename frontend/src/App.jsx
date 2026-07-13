import { BrowserRouter as Router, Routes, Route, Link, Navigate, useLocation } from 'react-router-dom'
import { useState, useEffect } from 'react'
import { BookOpen, ShieldCheck, LogOut, Lock, ArrowRight, FileText } from 'lucide-react' // DÜZELTME: FileText ikonu eklendi
import axios from 'axios'
import StudentDetail from './StudentDetail'
import StudentList from './StudentList'
import CourseManagement from './CourseManagement'
import JobLogs from './JobLogs' // DÜZELTME: JobLogs sayfası eklendi
import Login from './Login'
import Home from './Home'
import './App.css'

const Unauthorized = () => (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh', backgroundColor: '#f3f4f6', padding: '2rem' }}>
      <div className="card" style={{ textAlign: 'center', padding: '4rem 3rem', maxWidth: '480px', width: '100%', position: 'relative', overflow: 'hidden', boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04)', borderRadius: '1.5rem', border: '1px solid #e5e7eb' }}>

        <div style={{ position: 'absolute', top: '-10px', left: '50%', transform: 'translateX(-50%)', fontSize: '14rem', fontWeight: '900', color: '#fca5a5', opacity: '0.15', zIndex: 0, userSelect: 'none', letterSpacing: '-0.05em' }}>
          401
        </div>

        <div style={{ position: 'relative', zIndex: 1 }}>
          <div style={{ width: '80px', height: '80px', backgroundColor: '#fee2e2', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 2rem auto', boxShadow: '0 0 0 10px rgba(254, 226, 226, 0.5)' }}>
            <Lock size={36} color="#dc2626" />
          </div>

          <h2 style={{ color: '#111827', marginBottom: '1rem', fontSize: '2rem', fontWeight: '700', letterSpacing: '-0.02em' }}>
            Access Denied
          </h2>

          <p className="text-gray" style={{ marginBottom: '2.5rem', lineHeight: '1.6', fontSize: '1rem' }}>
            Oops! It looks like you're trying to enter a restricted area. Your session might have expired, or you don't have the necessary credentials.
          </p>

          <Link to="/login" className="btn-primary" style={{ textDecoration: 'none', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: '0.5rem', width: '100%', padding: '0.875rem', fontSize: '1rem', borderRadius: '0.75rem', transition: 'all 0.2s ease', backgroundColor: '#111827' }}>
            Secure Login <ArrowRight size={18} />
          </Link>
        </div>
      </div>
    </div>
);

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

const AppLayout = ({ authData, setAuthData, children }) => {
  const handleLogout = () => {
    localStorage.clear()
    delete axios.defaults.headers.common['Authorization']
    setAuthData({ token: null, role: null, name: null })
  }

  return (
      <div className="app-layout">
        <nav className="sidebar">
          <div className="brand">
            <BookOpen size={24} className="text-primary" />
            <h2>EduCore</h2>
          </div>

          <ul className="nav-links">
            <li><Link to="/">🏠 Home</Link></li>
            <li><Link to="/students">🎓 Students</Link></li>
            <li><Link to="/courses">📚 Courses</Link></li>
            {/* DÜZELTME: Sadece Admin ise Log menüsünü gösterir */}
            {authData.role === 'ADMIN' && (
                <li>
                  <Link to="/logs">
                    <FileText size={16} style={{display:'inline', verticalAlign:'middle', marginRight:'5px'}}/>
                    Job Logs
                  </Link>
                </li>
            )}
          </ul>

          <div className="user-profile">
            <ShieldCheck size={28} color={authData.role === 'ADMIN' ? '#10b981' : '#6b7280'} />
            <div style={{ flex: 1 }}>
              <p className="fw-500" style={{ fontSize: '13px', color: '#fff' }}>Logged in as:</p>
              <p style={{ fontSize: '12px', color: authData.role === 'ADMIN' ? '#10b981' : '#9ca3af' }}>{authData.name} ({authData.role})</p>
            </div>
          </div>

          <button onClick={handleLogout} className="btn-secondary" style={{ marginTop: '1rem', width: '100%', padding: '0.6rem', fontSize: '0.8rem', backgroundColor: '#fee2e2', color: '#b91c1c', border: 'none' }}>
            <LogOut size={14} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '5px' }} />
            Logout
          </button>
        </nav>

        <main className="content-area">
          {children}
        </main>
      </div>
  )
};

function App() {
  const [authData, setAuthData] = useState({
    token: localStorage.getItem('token'),
    role: localStorage.getItem('role'),
    name: localStorage.getItem('name')
  })

  useEffect(() => {
    if (authData.token) {
      axios.defaults.headers.common['Authorization'] = `Bearer ${authData.token}`
    }

    const interceptor = axios.interceptors.response.use(
        (response) => response,
        (error) => {
          if (error.response && error.response.status === 401) {
            localStorage.clear()
            delete axios.defaults.headers.common['Authorization']
            setAuthData({ token: null, role: null, name: null })
          }
          return Promise.reject(error)
        }
    )

    return () => axios.interceptors.response.eject(interceptor)
  }, [authData.token])

  return (
      <Router>
        <Routes>
          <Route path="/login" element={authData.token ? <Navigate to="/" replace /> : <Login setAuthData={setAuthData} />} />
          <Route path="/unauthorized" element={authData.token ? <Navigate to="/" replace /> : <Unauthorized />} />

          <Route path="/*" element={
            <ProtectedRoute authData={authData}>
              <AppLayout authData={authData} setAuthData={setAuthData}>
                <Routes>
                  <Route path="/" element={<Home authData={authData} />} />
                  <Route path="/students" element={<StudentList appMode={{ role: authData.role }} />} />
                  <Route path="/students/:id" element={<StudentDetail appMode={{ role: authData.role }} />} />
                  <Route path="/courses" element={<CourseManagement appMode={{ role: authData.role }} />} />
                  {/* DÜZELTME: Log sayfası rotası eklendi */}
                  <Route path="/logs" element={<JobLogs appMode={{ role: authData.role }} />} />
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