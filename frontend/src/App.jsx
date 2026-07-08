import { BrowserRouter as Router, Routes, Route, Navigate, Link } from 'react-router-dom'
import { useState } from 'react'
import { Shield, BookOpen, UserCircle } from 'lucide-react'
import StudentDetail from './StudentDetail'
import UserManagement from './UserManagement'
import StudentList from './StudentList' // YENİ EKLENEN İMPORT
import './App.css'

// --- KORUMALI ROTA (Mühendislik Standartı) ---
// Sadece Akademisyenlerin girmesi gereken sayfalarda kalkan görevi görür.
const ProtectedRoute = ({ children, currentUser }) => {
  if (currentUser.role !== 'ACADEMICIAN') {
    return <Navigate to="/unauthorized" replace />
  }
  return children
}

function App() {
  // GİRİŞ YAPAN KULLANICIYI SİMÜLE EDİYORUZ (Gerçekte bu JWT token'dan gelir)
  const [currentUser, setCurrentUser] = useState({
    id: 1,
    name: 'Prof. Dr. Ahmet Yılmaz',
    role: 'ACADEMICIAN' // Bunu 'STUDENT' yapıp test edebilirsin
  })

  return (
      <Router>
        <div className="app-layout">
          {/* YAN MENÜ (Sidebar) */}
          <nav className="sidebar">
            <div className="brand">
              <BookOpen size={24} className="text-primary" />
              <h2>EduCore</h2>
            </div>

            <ul className="nav-links">
              <li><Link to="/">🏠 Ana Sayfa</Link></li>

              {/* RBAC: Sadece Akademisyen menüde bu linkleri görebilir */}
              {currentUser.role === 'ACADEMICIAN' && (
                  <>
                    <li><Link to="/students">🎓 Öğrenci Yönetimi</Link></li>
                    <li><Link to="/users">🛡️ Yetkilendirme</Link></li>  {/* YENİ EKLENEN BUTON */}
                  </>
              )}
            </ul>

            <div className="user-profile">
              <UserCircle size={32} />
              <div>
                <p className="fw-500">{currentUser.name}</p>
                <p className="text-gray" style={{fontSize: '12px'}}>{currentUser.role}</p>
              </div>
            </div>
          </nav>

          {/* ANA İÇERİK ALANI */}
          <main className="content-area">
            <Routes>
              <Route path="/" element={<div className="card"><h2>Hoş Geldiniz, {currentUser.name}</h2><p>Sol menüden işleminizi seçin.</p></div>} />

              {/* KORUMALI ROTA UYGULANMIŞ SAYFALAR */}
              <Route
                  path="/students"
                  element={
                    <ProtectedRoute currentUser={currentUser}>
                      {/* YENİ EKLENEN BİLEŞEN BURADA */}
                      <StudentList />
                    </ProtectedRoute>
                  }
              />

              <Route
                  path="/students/:id"
                  element={
                    <ProtectedRoute currentUser={currentUser}>
                      <StudentDetail />
                    </ProtectedRoute>
                  }
              />

              <Route path="/unauthorized" element={<div className="card empty-state"><Shield size={48} color="red" /><h2>Erişim Reddedildi</h2><p>Bu sayfayı görüntüleme yetkiniz yok.</p></div>} />
            </Routes>
          </main>
        </div>
      </Router>
  )
}

export default App