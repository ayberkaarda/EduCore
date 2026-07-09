import { BrowserRouter as Router, Routes, Route, Link } from 'react-router-dom'
import { useState } from 'react'
import { BookOpen, Settings, RefreshCcw, ShieldCheck } from 'lucide-react'
import StudentDetail from './StudentDetail'
import StudentList from './StudentList'
import CourseManagement from './CourseManagement'
import './App.css'

// Global Uygulama Modları
const APP_MODES = {
  ADMIN: { role: 'ADMIN', label: 'Admin (Full Access)' },
  USER: { role: 'USER', label: 'User (View Only)' }
}

function App() {
  const [appMode, setAppMode] = useState(APP_MODES.ADMIN)

  // Tek tuşla Admin/User arası geçiş
  const toggleMode = () => setAppMode(appMode.role === 'ADMIN' ? APP_MODES.USER : APP_MODES.ADMIN)

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

            {/* Alt Taraf: Mod Göstergesi ve Değiştirme Butonu */}
            <div className="user-profile">
              <ShieldCheck size={28} color={appMode.role === 'ADMIN' ? '#10b981' : '#6b7280'} />
              <div style={{ flex: 1 }}>
                <p className="fw-500" style={{ fontSize: '13px', color: '#fff' }}>Current Mode:</p>
                <p style={{ fontSize: '12px', color: appMode.role === 'ADMIN' ? '#10b981' : '#9ca3af' }}>{appMode.label}</p>
              </div>
            </div>

            <button onClick={toggleMode} className="btn-secondary" style={{ marginTop: '1rem', width: '100%', padding: '0.6rem', fontSize: '0.8rem', backgroundColor: '#1f2937', color: 'white', border: '1px solid #374151' }}>
              <RefreshCcw size={14} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '5px' }} />
              Switch to {appMode.role === 'ADMIN' ? 'User' : 'Admin'}
            </button>
          </nav>

          <main className="content-area">
            <Routes>
              <Route path="/" element={<div className="card"><h2>Welcome to EduCore</h2><p>You are viewing the system in <strong>{appMode.label}</strong>.</p></div>} />
              {/* Uygulama modunu sayfalara gönderiyoruz ki yetkileri bilsinler */}
              <Route path="/students" element={<StudentList appMode={appMode} />} />
              <Route path="/students/:id" element={<StudentDetail appMode={appMode} />} />
              <Route path="/courses" element={<CourseManagement appMode={appMode} />} />
            </Routes>
          </main>
        </div>
      </Router>
  )
}

export default App