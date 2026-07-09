import { BrowserRouter as Router, Routes, Route, Link, Navigate } from 'react-router-dom'
import { useState, useEffect } from 'react'
import { BookOpen, ShieldCheck, LogOut } from 'lucide-react'
import axios from 'axios'
import StudentDetail from './StudentDetail'
import StudentList from './StudentList'
import CourseManagement from './CourseManagement'
import Login from './Login'
import Home from './Home'
import './App.css'

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

    // GÜVENLİK: Eğer sunucu 401 Unauthorized dönerse (token süresi dolduysa) otomatik çıkış yap
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

  const handleLogout = () => {
    localStorage.clear()
    delete axios.defaults.headers.common['Authorization']
    setAuthData({ token: null, role: null, name: null })
  }

  if (!authData.token) return <Login setAuthData={setAuthData} />

  return (
      <Router>
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
            <Routes>
              <Route path="/" element={<Home authData={authData} />} />
              <Route path="/students" element={<StudentList appMode={{ role: authData.role }} />} />
              <Route path="/students/:id" element={<StudentDetail appMode={{ role: authData.role }} />} />
              <Route path="/courses" element={<CourseManagement appMode={{ role: authData.role }} />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </main>
        </div>
      </Router>
  )
}

export default App