import { useState, useEffect } from 'react'
import axios from 'axios'
import toast, { Toaster } from 'react-hot-toast'
import { CheckCircle2, PlusCircle, Book, Calendar, UserCheck } from 'lucide-react'

const API_BASE = 'http://localhost:8081/api/v1'

export default function StudentProfile({ currentUser }) {
    const [allCourses, setAllCourses] = useState([])
    const [enrolledCourses, setEnrolledCourses] = useState([])
    const [isEnrolling, setIsEnrolling] = useState(false)

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
            toast.error('Failed to load course data.')
        }
    }

    const handleEnroll = async (courseId) => {
        setIsEnrolling(true)
        try {
            await axios.post(`${API_BASE}/enroll`, { accountId: currentUser.id, courseId: courseId })
            toast.success('Course successfully selected!')
            fetchData()
        } catch (error) {
            toast.error(error.response?.data?.error || 'Error occurred while selecting the course.')
        } finally {
            setIsEnrolling(false)
        }
    }

    return (
        <div className="student-detail-wrapper">
            <Toaster />
            <div className="detail-header">
                <h2><UserCheck size={28} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '10px', color: '#4f46e5' }} /> My Student Profile</h2>
                <p className="text-gray">Welcome {currentUser.name}, you can select your term courses below.</p>
            </div>

            <div className="course-grid">
                <div className="card">
                    <h3 className="section-title">
                        <CheckCircle2 size={20} color="#10b981" /> My Enrolled Courses
                    </h3>
                    <div className="course-list">
                        {enrolledCourses.length === 0 ? (
                            <p className="empty-state text-gray">You haven't selected any courses yet.</p>
                        ) : (
                            enrolledCourses.map(course => (
                                <div key={course.id} className="course-item active">
                                    <div className="course-info">
                                        <h4>{course.name}</h4>
                                        <span><Calendar size={14} /> {course.term}</span>
                                    </div>
                                    <span className="badge success">Enrolled</span>
                                </div>
                            ))
                        )}
                    </div>
                </div>

                <div className="card">
                    <h3 className="section-title">
                        <Book size={20} color="#4f46e5" /> Available Course Catalog
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
                                        {isAlreadyEnrolled ? 'Selected' : <><PlusCircle size={16} /> Select</>}
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