import { useState, useEffect } from 'react'
import axios from 'axios'
import toast, { Toaster } from 'react-hot-toast'
import { CheckCircle2, PlusCircle, Book, Calendar, UserCheck } from 'lucide-react'

const API_BASE = 'http://localhost:8080/api/v1'

export default function StudentProfile({ currentUser }) {
    const [allCourses, setAllCourses] = useState([])
    const [enrolledCourses, setEnrolledCourses] = useState([])
    const [isEnrolling, setIsEnrolling] = useState(false)

    // Kullanıcı değiştiğinde verileri otomatik yenile
    useEffect(() => {
        fetchData()
    }, [currentUser.id])

    const fetchData = async () => {
        try {
            const [coursesRes, enrolledRes] = await Promise.all([
                axios.get(`${API_BASE}/courses`),
                axios.get(`${API_BASE}/accounts/${currentUser.id}/courses`)
            ])
            setAllCourses(coursesRes.data)
            setEnrolledCourses(enrolledRes.data)
        } catch (error) {
            toast.error('Ders verileri çekilemedi.')
        }
    }

    const handleEnroll = async (courseId) => {
        setIsEnrolling(true)
        try {
            await axios.post(`${API_BASE}/enroll`, { accountId: currentUser.id, courseId: courseId })
            toast.success('Ders başarıyla seçildi!')
            fetchData() // Listeyi tazele
        } catch (error) {
            // Backend'den gelen GlobalExceptionHandler hatasını yakala
            toast.error(error.response?.data?.error || 'Ders seçilirken hata oluştu.')
        } finally {
            setIsEnrolling(false)
        }
    }

    return (
        <div className="student-detail-wrapper">
            <Toaster />
            <div className="detail-header">
                <h2><UserCheck size={28} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '10px', color: '#4f46e5' }} /> Öğrenci Profilim</h2>
                <p className="text-gray">Hoş geldin {currentUser.name}, aşağıdan dönem derslerini seçebilirsin.</p>
            </div>

            <div className="course-grid">
                {/* SOL: Seçtiğim Dersler */}
                <div className="card">
                    <h3 className="section-title">
                        <CheckCircle2 size={20} color="#10b981" /> Seçtiğim Dersler
                    </h3>
                    <div className="course-list">
                        {enrolledCourses.length === 0 ? (
                            <p className="empty-state text-gray">Henüz hiçbir ders seçmedin.</p>
                        ) : (
                            enrolledCourses.map(course => (
                                <div key={course.id} className="course-item active">
                                    <div className="course-info">
                                        <h4>{course.name}</h4>
                                        <span><Calendar size={14} /> {course.term}</span>
                                    </div>
                                    <span className="badge success">Kayıtlı</span>
                                </div>
                            ))
                        )}
                    </div>
                </div>

                {/* SAĞ: Açık Ders Kataloğu */}
                <div className="card">
                    <h3 className="section-title">
                        <Book size={20} color="#4f46e5" /> Açık Ders Kataloğu
                    </h3>
                    <div className="course-list">
                        {allCourses.map(course => {
                            const isAlreadyEnrolled = enrolledCourses.some(ec => ec.id === course.id)

                            return (
                                <div key={course.id} className={`course-item ${isAlreadyEnrolled ? 'disabled' : ''}`}>
                                    <div className="course-info">
                                        <h4>{course.name}</h4>
                                        <span><Calendar size={14} /> {course.term}</span>
                                    </div>

                                    <button
                                        className={`btn-primary add-btn ${isAlreadyEnrolled ? 'btn-disabled' : ''}`}
                                        onClick={() => handleEnroll(course.id)}
                                        disabled={isAlreadyEnrolled || isEnrolling}
                                    >
                                        {isAlreadyEnrolled ? 'Seçildi' : <><PlusCircle size={16} /> Seç</>}
                                    </button>
                                </div>
                            )
                        })}
                    </div>
                </div>
            </div>
        </div>
    )
}