import { useState, useEffect } from 'react'
import axios from 'axios'
import toast, { Toaster } from 'react-hot-toast'
import { Book, Plus, Loader2, Calendar, Edit, Trash2, User } from 'lucide-react'

const API_BASE = 'http://localhost:8081/api/v1'

export default function CourseManagement({ appMode }) {
    const isAdmin = appMode.role === 'ADMIN'

    const [courses, setCourses] = useState([])
    const [isLoading, setIsLoading] = useState(true)

    const [isModalOpen, setIsModalOpen] = useState(false)
    const [isEditModalOpen, setIsEditModalOpen] = useState(false)

    const [newCourse, setNewCourse] = useState({ name: '', term: '', instructor: '' })
    const [editCourse, setEditCourse] = useState({ id: null, name: '', term: '', instructor: '' })

    useEffect(() => { fetchData() }, [])

    const fetchData = async () => {
        setIsLoading(true)
        try {
            const res = await axios.get(`${API_BASE}/courses`)
            setCourses(res.data)
        } catch (error) { toast.error('Failed to load courses.') }
        finally { setIsLoading(false) }
    }

    const handleCreate = async (e) => {
        e.preventDefault()
        try {
            await axios.post(`${API_BASE}/courses`, newCourse)
            toast.success('Course successfully added!')
            setIsModalOpen(false)
            setNewCourse({ name: '', term: '', instructor: '' })
            fetchData()
        } catch (error) { toast.error('Error occurred.') }
    }

    const handleDelete = async (id) => {
        if(!window.confirm('Are you sure you want to delete this course?')) return;
        try {
            await axios.delete(`${API_BASE}/courses/${id}`)
            toast.success('Course deleted successfully!')
            fetchData()
        } catch (error) { toast.error('Failed to delete course.') }
    }

    const openEditModal = (course) => {
        setEditCourse({ id: course.id, name: course.name, term: course.term, instructor: course.instructor || '' })
        setIsEditModalOpen(true)
    }

    const handleUpdate = async (e) => {
        e.preventDefault()
        try {
            await axios.put(`${API_BASE}/courses/${editCourse.id}`, { name: editCourse.name, term: editCourse.term, instructor: editCourse.instructor })
            toast.success('Course updated successfully!')
            setIsEditModalOpen(false)
            fetchData()
        } catch (error) { toast.error('Failed to update course.') }
    }

    return (
        <div className="card">
            <Toaster />
            <div className="detail-header">
                <div>
                    <h2><Book size={28} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '10px', color: '#4f46e5' }} /> Course Catalog</h2>
                    <p className="text-gray">View all active courses in the system.</p>
                </div>
                {isAdmin && (
                    <button className="btn-primary" onClick={() => setIsModalOpen(true)}>
                        <Plus size={18} /> New Course
                    </button>
                )}
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: '1.5rem', marginTop: '2rem' }}>
                {isLoading ? <Loader2 className="spin text-gray" /> : courses.map(c => (
                    <div key={c.id} className="course-item" style={{ position: 'relative', display: 'flex', flexDirection: 'column', alignItems: 'flex-start', padding: '1.5rem', border: '1px solid #e5e7eb', borderRadius: '0.75rem', backgroundColor: '#f9fafb' }}>

                        {isAdmin && (
                            <div style={{ position: 'absolute', top: '1rem', right: '1rem', display: 'flex', gap: '0.5rem' }}>
                                <button className="btn-secondary" onClick={() => openEditModal(c)} style={{ padding: '0.3rem', backgroundColor: '#fef3c7', color: '#b45309', border: 'none' }}><Edit size={14} /></button>
                                <button className="btn-secondary" onClick={() => handleDelete(c.id)} style={{ padding: '0.3rem', backgroundColor: '#fee2e2', color: '#b91c1c', border: 'none' }}><Trash2 size={14} /></button>
                            </div>
                        )}

                        <h4 style={{ margin: '0 0 0.75rem 0', fontSize: '1.1rem', color: '#111827', paddingRight: '3rem' }}>{c.name}</h4>

                        {/* DÜZELTME: Rozetler (Badges) yan yana hizalandı */}
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem' }}>
                            <span className="badge" style={{ backgroundColor: '#e0e7ff', color: '#3730a3', display: 'flex', alignItems: 'center', gap: '4px' }}>
                                <Calendar size={12} /> Term: {c.term}
                            </span>
                            <span className="badge" style={{ backgroundColor: '#d1fae5', color: '#065f46', display: 'flex', alignItems: 'center', gap: '4px' }}>
                                <User size={12} /> {c.instructor || 'Not Assigned'}
                            </span>
                        </div>
                    </div>
                ))}
            </div>

            {isModalOpen && (
                <div className="modal-overlay">
                    <div className="modal-content">
                        <h3 style={{ marginTop: 0 }}>Create New Course</h3>
                        <form onSubmit={handleCreate}>
                            <div className="form-group"><label>Course Name</label><input required type="text" value={newCourse.name} onChange={e => setNewCourse({...newCourse, name: e.target.value})} /></div>
                            <div className="form-group"><label>Term</label><input required type="text" value={newCourse.term} onChange={e => setNewCourse({...newCourse, term: e.target.value})} /></div>
                                <div className="form-group"><label>Instructor Name</label><input type="text" placeholder="e.g. Ayberk Arda" value={newCourse.instructor} onChange={e => setNewCourse({...newCourse, instructor: e.target.value})} /></div>
                            <div className="modal-actions"><button type="button" className="btn-secondary" onClick={() => setIsModalOpen(false)}>Cancel</button><button type="submit" className="btn-primary">Save</button></div>
                        </form>
                    </div>
                </div>
            )}

            {isEditModalOpen && (
                <div className="modal-overlay">
                    <div className="modal-content">
                        <h3 style={{ marginTop: 0 }}>Edit Course</h3>
                        <form onSubmit={handleUpdate}>
                            <div className="form-group"><label>Course Name</label><input required type="text" value={editCourse.name} onChange={e => setEditCourse({...editCourse, name: e.target.value})} /></div>
                            <div className="form-group"><label>Term</label><input required type="text" value={editCourse.term} onChange={e => setEditCourse({...editCourse, term: e.target.value})} /></div>
                            <div className="form-group"><label>Instructor Name</label><input type="text" value={editCourse.instructor} onChange={e => setEditCourse({...editCourse, instructor: e.target.value})} /></div>
                            <div className="modal-actions"><button type="button" className="btn-secondary" onClick={() => setIsEditModalOpen(false)}>Cancel</button><button type="submit" className="btn-primary" style={{backgroundColor: '#f59e0b'}}>Update</button></div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    )
}