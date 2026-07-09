import { useState, useEffect } from 'react'
import axios from 'axios'
import { Users, BookOpen, Clock, Loader2 } from 'lucide-react'

const API_BASE = 'http://localhost:8080/api/v1'

export default function Home({ authData }) {
    const [stats, setStats] = useState({ totalStudents: 0, totalCourses: 0, recentStudents: [] })
    const [isLoading, setIsLoading] = useState(true)

    useEffect(() => {
        const fetchStats = async () => {
            try {
                const [studentsRes, coursesRes] = await Promise.all([
                    axios.get(`${API_BASE}/accounts/students?page=0&size=5`), // Sadece son 5 kişi
                    axios.get(`${API_BASE}/courses`)
                ])
                setStats({
                    totalStudents: studentsRes.data.totalElements,
                    totalCourses: coursesRes.data.length,
                    recentStudents: studentsRes.data.content
                })
            } catch (error) {
                console.error("Dashboard yüklenemedi", error)
            } finally {
                setIsLoading(false)
            }
        }
        fetchStats()
    }, [])

    if (isLoading) return <div className="empty-state"><Loader2 className="spin text-gray" size={40} /></div>

    return (
        <div className="student-detail-wrapper">
            <div className="detail-header" style={{ marginBottom: '2rem' }}>
                <h2>Welcome to EduCore Dashboard, {authData.name}!</h2>
                <p className="text-gray">System overview and real-time statistics.</p>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))', gap: '1.5rem', marginBottom: '2rem' }}>
                <div className="card" style={{ display: 'flex', alignItems: 'center', gap: '1.5rem', margin: 0, padding: '1.5rem' }}>
                    <div style={{ padding: '1rem', backgroundColor: '#e0e7ff', borderRadius: '1rem', color: '#4f46e5' }}><Users size={32}/></div>
                    <div><h3 style={{ margin: 0, fontSize: '1.8rem' }}>{stats.totalStudents}</h3><p style={{ margin: 0, color: '#6b7280', fontWeight: 500 }}>Total Students</p></div>
                </div>
                <div className="card" style={{ display: 'flex', alignItems: 'center', gap: '1.5rem', margin: 0, padding: '1.5rem' }}>
                    <div style={{ padding: '1rem', backgroundColor: '#d1fae5', borderRadius: '1rem', color: '#059669' }}><BookOpen size={32}/></div>
                    <div><h3 style={{ margin: 0, fontSize: '1.8rem' }}>{stats.totalCourses}</h3><p style={{ margin: 0, color: '#6b7280', fontWeight: 500 }}>Active Courses</p></div>
                </div>
            </div>

            <div className="card">
                <h3 className="section-title"><Clock size={20} color="#4f46e5"/> Recently Added Students</h3>
                <div className="table-responsive">
                    <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                        <thead>
                        <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Student ID</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Full Name</th>
                        </tr>
                        </thead>
                        <tbody>
                        {stats.recentStudents.map((s) => (
                            <tr key={s.id} style={{ borderBottom: '1px solid #e5e7eb' }}>
                                <td style={{ padding: '1rem', fontWeight: '500' }}>#{s.studentNumber}</td>
                                <td style={{ padding: '1rem' }}>{s.firstName} {s.lastName}</td>
                            </tr>
                        ))}
                        {stats.recentStudents.length === 0 && <tr><td colSpan="2" style={{ padding: '1rem' }}>No students found.</td></tr>}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    )
}