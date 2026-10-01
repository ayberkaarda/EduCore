import { api, apiError } from './api'
import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import axios from 'axios'
import toast from 'react-hot-toast'
import Toaster from './Toasts'
import { ArrowLeft, PlusCircle, Calendar, Book } from 'lucide-react'



export default function StudentDetail() {
    const { id } = useParams()
    const navigate = useNavigate()

    const [allCourses, setAllCourses] = useState([])
    const [enrolledCourses, setEnrolledCourses] = useState([])
    const [isEnrolling, setIsEnrolling] = useState(false)

    const fetchData = useCallback(() => Promise.all([
        axios.get(api.courses),
        axios.get(api.accountEnrollments(id))
    ])
        .then(([coursesRes, enrolledRes]) => {
            setAllCourses(coursesRes.data)
            setEnrolledCourses(enrolledRes.data)
        })
        .catch((error) => toast.error(apiError(error, 'Failed to load course data.'))), [id])

    useEffect(() => {
        fetchData()
    }, [fetchData])

    const handleEnroll = async (courseId) => {
        setIsEnrolling(true)
        try {
            await axios.post(api.accountEnrollments(id), { courseId })
            toast.success('Course added.')
            await fetchData()
        } catch (error) {
            toast.error(apiError(error, 'Error occurred while adding the course.'))
        } finally {
            setIsEnrolling(false)
        }
    }

    return (
        <div className="student-detail-wrapper">
            <Toaster />
            <button className="btn-secondary back-btn" onClick={() => navigate(-1)}>
                <ArrowLeft size={18} /> Back
            </button>

            <div className="detail-header">
                <h2>Student courses</h2>
                <p className="text-gray">Record identifier: {id}</p>
            </div>

            <div className="course-grid">
                <div className="card">
                    <h3 className="section-title"> Enrolled courses
                    </h3>
                    <div className="course-list">
                        {enrolledCourses.length === 0 ? (
                            <p className="empty-state text-gray">The student has not enrolled in any courses yet.</p>
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
                    <div className="split-row">
                        <h3 className="section-title"> Course catalogue
                        </h3>
                    </div>

                    <div className="course-list">
                        {allCourses.length === 0 && <div className="empty-state"><Book size={24}/><h4>No courses yet.</h4><p>Available courses will appear here.</p></div>}
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
                                        {isAlreadyEnrolled ? 'Enrolled' : <><PlusCircle size={16} /> Enrol</>}
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