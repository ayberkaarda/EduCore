import { api, apiError } from './api'
import { useState, useEffect } from 'react'
import axios from 'axios'
import { Loader2, Users } from 'lucide-react'
import { Link } from 'react-router-dom'



export default function Home({ authData }) {
    const [stats, setStats] = useState({ totalStudents: 0, totalCourses: 0, recentStudents: [] })
    const [loadError, setLoadError] = useState('')
    const [isLoading, setIsLoading] = useState(true)

    useEffect(() => {
        const fetchStats = async () => {
            try {
                const [studentsRes, coursesRes] = await Promise.all([
                    axios.get(api.students + '?page=0&size=5'), // Sadece son 5 kişi
                    axios.get(api.courses)
                ])
                setStats({
                    totalStudents: studentsRes.data.totalElements,
                    totalCourses: coursesRes.data.length,
                    recentStudents: studentsRes.data.content
                })
            } catch (error) {
                console.error("Dashboard yüklenemedi", error)
                setLoadError(apiError(error, 'Could not load the registry overview. Check your connection and refresh the page.'))
            } finally {
                setIsLoading(false)
            }
        }
        fetchStats()
    }, [])

    if (isLoading) return <div className="empty-state"><Loader2 className="spin text-gray" size={40} /></div>

    return (
        <div className="student-detail-wrapper" aria-label={'Registry overview for '+authData.name}>
            <div className="detail-header"><div><h2>Dashboard</h2><p className="text-gray">Registry overview</p></div></div>
            {loadError && <p className="inline-error" role="alert">{loadError}</p>}
            <div className="stat-grid"><article className="card stat-tile"><p>Total students</p><h3>{stats.totalStudents}</h3><span>Student records</span></article><article className="card stat-tile"><p>Active courses</p><h3>{stats.totalCourses}</h3><span>Course catalogue</span></article></div>
            <div className="card">
                <div className="split-row"><h3 className="section-title">Recently added students</h3><Link to="/students">View all students</Link></div>
                <div className="table-responsive">
                    <table>
                        <thead>
                        <tr>
                            <th>Student number</th>
                            <th>Name</th>
                        </tr>
                        </thead>
                        <tbody>
                        {stats.recentStudents.map((s) => (
                            <tr key={s.id}>
                                <td>#{s.studentNumber}</td>
                                <td className="text-left">{s.firstName} {s.lastName}</td>
                            </tr>
                        ))}
                        {stats.recentStudents.length === 0 && <tr><td colSpan="2"><div className="empty-state"><Users size={24}/><h4>No students yet.</h4><p>Add a student or run a CSV import.</p></div></td></tr>}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    )
}