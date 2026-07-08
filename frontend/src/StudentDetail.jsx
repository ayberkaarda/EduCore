import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import axios from 'axios'
import toast, { Toaster } from 'react-hot-toast'
import { ArrowLeft, CheckCircle2, PlusCircle, Book, Calendar } from 'lucide-react'

const API_BASE = 'http://localhost:8080/api/v1'

export default function StudentDetail() {
    const { id } = useParams() // URL'den öğrenci ID'sini alır
    const navigate = useNavigate()

    const [allCourses, setAllCourses] = useState([])
    const [enrolledCourses, setEnrolledCourses] = useState([])
    const [isEnrolling, setIsEnrolling] = useState(false)

    // Sayfa açıldığında dersleri çeker
    useEffect(() => {
        fetchData()
    }, [id])

    const fetchData = async () => {
        try {
            // Promise.all ile iki API'ye aynı anda paralel istek atıyoruz (Performans!)
            const [coursesRes, enrolledRes] = await Promise.all([
                axios.get(`${API_BASE}/courses`),
                axios.get(`${API_BASE}/accounts/${id}/courses`)
            ])
            setAllCourses(coursesRes.data)
            setEnrolledCourses(enrolledRes.data)
        } catch (error) {
            toast.error('Ders verileri çekilemedi.')
        }
    }

    // Ders ekleme işlemi
    const handleEnroll = async (courseId) => {
        setIsEnrolling(true)
        try {
            await axios.post(`${API_BASE}/enroll`, { accountId: id, courseId: courseId })
            toast.success('Ders başarıyla eklendi!')
            fetchData() // Listeyi tazelemek için tekrar veri çek
        } catch (error) {
            toast.error(error.response?.data || 'Ders eklenirken hata oluştu.')
        } finally {
            setIsEnrolling(false)
        }
    }

    return (
        <div className="student-detail-wrapper">
            <Toaster />
            <button className="btn-secondary back-btn" onClick={() => navigate(-1)}>
                <ArrowLeft size={18} /> Geri Dön
            </button>

            <div className="detail-header">
                <h2>Öğrenci Profil & Ders Kaydı</h2>
                <p className="text-gray">Öğrenci Numarası: #STU-{id}</p>
            </div>

            <div className="course-grid">
                {/* SOL: Alınan Dersler */}
                <div className="card">
                    <h3 className="section-title">
                        <CheckCircle2 size={20} color="#10b981" /> Alınan Dersler
                    </h3>
                    <div className="course-list">
                        {enrolledCourses.length === 0 ? (
                            <p className="empty-state text-gray">Öğrenci henüz hiç ders almamış.</p>
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

                {/* SAĞ: Eklenebilecek Tüm Dersler */}
                <div className="card">
                    <h3 className="section-title">
                        <Book size={20} color="#4f46e5" /> Tüm Ders Kataloğu
                    </h3>
                    <div className="course-list">
                        {allCourses.map(course => {
                            // UX MÜHENDİSLİĞİ: Öğrenci bu dersi almış mı?
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
                                        {isAlreadyEnrolled ? 'Alındı' : <><PlusCircle size={16} /> Ekle</>}
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