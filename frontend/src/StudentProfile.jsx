import { api, apiError } from './api'
import { useState, useEffect, useCallback } from 'react'
import axios from 'axios'
import toast from 'react-hot-toast'
import Toaster from './Toasts'
import { PlusCircle, Calendar, Book } from 'lucide-react'



export default function StudentProfile({ currentUser }) {
    const [profile, setProfile] = useState(null)
    const [allCourses, setAllCourses] = useState([])
    const [enrolledCourses, setEnrolledCourses] = useState([])
    const [isEnrolling, setIsEnrolling] = useState(false)

    const fetchData = useCallback(() => Promise.all([
        axios.get(api.courses),
        axios.get(api.enrollments),
        axios.get(api.me)
    ])
        .then(([coursesRes, enrolledRes, profileRes]) => {
            setProfile(profileRes.data)
            setAllCourses(coursesRes.data)
            setEnrolledCourses(enrolledRes.data)
        })
        .catch((error) => toast.error(apiError(error, 'Failed to load course data.'))), [])

    useEffect(() => {
        fetchData()
    }, [fetchData])

    const handleEnroll = async (courseId) => {
        setIsEnrolling(true)
        try {
            await axios.post(api.enrollments, { courseId })
            toast.success('Course selected.')
            await fetchData()
        } catch (error) {
            toast.error(apiError(error, 'Error occurred while selecting the course.'))
        } finally {
            setIsEnrolling(false)
        }
    }

    return (
        <div className="student-detail-wrapper">
            <Toaster />
            <div className="detail-header">
                <h2>My profile</h2>
                <p className="text-gray">Your details and term courses</p>
            </div>

            <section className="card profile-details"><h3>{profile ? [profile.firstName, profile.lastName].filter(Boolean).join(' ') : currentUser.name}</h3><p className="mono">{profile?.studentNumber || profile?.username || profile?.id}</p><span className={(profile?.role || currentUser.role) === 'ADMIN' ? 'badge' : 'badge neutral'}>{(profile?.role || currentUser.role) === 'ADMIN' ? 'Administrator' : 'User'}</span></section>
            <div className="course-grid">
                <div className="card">
                    <h3 className="section-title"> Enrolled courses
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
                    <h3 className="section-title"> Course catalogue
                    </h3>
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