import { BrowserRouter as Router, Routes, Route, Navigate, Link } from 'react-router-dom'
import { useState } from 'react'
import { Shield, BookOpen, UserCircle, RefreshCcw } from 'lucide-react'
import StudentDetail from './StudentDetail'
import StudentList from './StudentList'
import UserManagement from './UserManagement'
import StudentProfile from './StudentProfile'
import CourseManagement from './CourseManagement'
import './App.css'

const ACADEMICIAN_USER = { id: 4, name: 'Prof. Dr. Ahmet Smith', role: 'ACADEMICIAN' }
const STUDENT_USER = { id: 1, name: 'Ayberk Arda', role: 'STUDENT' }

const AcademicianRoute = ({ children, currentUser }) => {
  if (currentUser.role !== 'ACADEMICIAN') return <Navigate to="/unauthorized" replace />
  return children
}

const StudentRoute = ({ children, currentUser }) => {
  if (currentUser.role !== 'STUDENT') return <Navigate to="/unauthorized" replace />
  return children
}

function App() {
  const [currentUser, setCurrentUser] = useState(ACADEMICIAN_USER)

  const toggleRole = () => {
    setCurrentUser(currentUser.role === 'ACADEMICIAN' ? STUDENT_USER : ACADEMICIAN_USER)
  }

  return (
      <Router>
        <div className="app-layout">
          <nav className="sidebar">
            <div className="brand">
              <BookOpen size={24} className="text-primary" />
              <h2 style={{color: "white"}}>EduCore</h2>
            </div>

            <ul className="nav-links">
              <li><Link to="/">🏠 Home</Link></li>

              {currentUser.role === 'ACADEMICIAN' && (
                  <>
                    <li><Link to="/students">🎓 Student Management</Link></li>
                    <li><Link to="/courses">📚 Course Management</Link></li>
                    <li><Link to="/users">🛡️ Role Management</Link></li>
                  </>
              )}

              {currentUser.role === 'STUDENT' && (
                  <li><Link to="/my-profile">👤 My Profile & Courses</Link></li>
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

            <button
                onClick={toggleRole}
                style={{ marginTop: '1rem', width: '100%', padding: '0.5rem', backgroundColor: '#374151', color: 'white', fontSize: '12px', border: '1px solid #4b5563', borderRadius: '0.5rem', cursor: 'pointer' }}
            >
              <RefreshCcw size={14} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '4px' }}/>
              {currentUser.role === 'ACADEMICIAN' ? 'Switch to Student' : 'Switch to Academician'}
            </button>
          </nav>

          <main className="content-area">
            <Routes>
              <Route path="/" element={<div className="card"><h2>Welcome, {currentUser.name}</h2><p>Please select an action from the left menu. Use the switch button below to test the roles.</p></div>} />

              <Route path="/students" element={<AcademicianRoute currentUser={currentUser}><StudentList /></AcademicianRoute>} />
              <Route path="/students/:id" element={<AcademicianRoute currentUser={currentUser}><StudentDetail /></AcademicianRoute>} />
              <Route path="/users" element={<AcademicianRoute currentUser={currentUser}><UserManagement /></AcademicianRoute>} />
              <Route path="/courses" element={<AcademicianRoute currentUser={currentUser}><CourseManagement /></AcademicianRoute>} />

              <Route path="/my-profile" element={<StudentRoute currentUser={currentUser}><StudentProfile currentUser={currentUser} /></StudentRoute>} />

              <Route path="/unauthorized" element={<div className="card empty-state"><Shield size={48} color="red" /><h2>Access Denied</h2><p>You do not have permission to view this page.</p></div>} />
            </Routes>
          </main>
        </div>
      </Router>
  )
}

export default App