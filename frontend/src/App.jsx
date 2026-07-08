import { BrowserRouter as Router, Routes, Route, Navigate, Link } from 'react-router-dom'
import { useState } from 'react'
import { Shield, BookOpen, UserCircle, RefreshCcw } from 'lucide-react'
import StudentDetail from './StudentDetail'
import StudentList from './StudentList'
import UserManagement from './UserManagement'
import StudentProfile from './StudentProfile' // YENİ EKLENDİ
import './App.css'

// Demo Kullanıcıları (Backend Seeder'daki ID'lere göre ayarlandı)
const ACADEMICIAN_USER = { id: 4, name: 'Prof. Dr. Ahmet Hoca', role: 'ACADEMICIAN' }
const STUDENT_USER = { id: 1, name: 'Ayberk Arda', role: 'STUDENT' }

// Akademisyen Koruması
const AcademicianRoute = ({ children, currentUser }) => {
  if (currentUser.role !== 'ACADEMICIAN') return <Navigate to="/unauthorized" replace />
  return children
}

// Öğrenci Koruması
const StudentRoute = ({ children, currentUser }) => {
  if (currentUser.role !== 'STUDENT') return <Navigate to="/unauthorized" replace />
  return children
}

function App() {
  const [currentUser, setCurrentUser] = useState(ACADEMICIAN_USER)

  // DEMO İÇİN KULLANICI DEĞİŞTİRME FONKSİYONU
  const toggleRole = () => {
    setCurrentUser(currentUser.role === 'ACADEMICIAN' ? STUDENT_USER : ACADEMICIAN_USER)
  }

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

              {/* SADECE AKADEMİSYEN GÖREBİLİR */}
              {currentUser.role === 'ACADEMICIAN' && (
                  <>
                    <li><Link to="/students">🎓 Öğrenci Yönetimi</Link></li>
                    <li><Link to="/users">🛡️ Yetkilendirme</Link></li>
                  </>
              )}

              {/* SADECE ÖĞRENCİ GÖREBİLİR */}
              {currentUser.role === 'STUDENT' && (
                  <li><Link to="/my-profile">👤 Profilim & Derslerim</Link></li>
              )}
            </ul>

            <div className="user-profile">
              <UserCircle size={32} />
              <div style={{ flex: 1 }}>
                <p className="fw-500" style={{ fontSize: '14px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                  {currentUser.name}
                </p>
                <p className="text-gray" style={{fontSize: '12px'}}>{currentUser.role}</p>
              </div>
            </div>

            {/* PORTFOLYO ŞOVU: HIZLI ROL DEĞİŞTİRİCİ */}
            <button
                onClick={toggleRole}
                style={{ marginTop: '1rem', width: '100%', padding: '0.5rem', backgroundColor: '#374151', color: 'white', fontSize: '12px', border: '1px solid #4b5563' }}
            >
              <RefreshCcw size={14} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '4px' }}/>
              {currentUser.role === 'ACADEMICIAN' ? 'Öğrenci Hesabına Geç' : 'Hoca Hesabına Geç'}
            </button>
          </nav>

          {/* ANA İÇERİK ALANI */}
          <main className="content-area">
            <Routes>
              <Route path="/" element={<div className="card"><h2>Hoş Geldiniz, {currentUser.name}</h2><p>Sol menüden işleminizi seçin. Portfolyoyu test etmek için sol alttaki 'Hesap Değiştir' butonunu kullanabilirsiniz.</p></div>} />

              {/* AKADEMİSYEN ROTALARI */}
              <Route path="/students" element={<AcademicianRoute currentUser={currentUser}><StudentList /></AcademicianRoute>} />
              <Route path="/students/:id" element={<AcademicianRoute currentUser={currentUser}><StudentDetail /></AcademicianRoute>} />
              <Route path="/users" element={<AcademicianRoute currentUser={currentUser}><UserManagement /></AcademicianRoute>} />

              {/* ÖĞRENCİ ROTALARI */}
              <Route path="/my-profile" element={<StudentRoute currentUser={currentUser}><StudentProfile currentUser={currentUser} /></StudentRoute>} />

              <Route path="/unauthorized" element={<div className="card empty-state"><Shield size={48} color="red" /><h2>Erişim Reddedildi</h2><p>Bu sayfayı görüntüleme yetkiniz yok.</p></div>} />
            </Routes>
          </main>
        </div>
      </Router>
  )
}

export default App