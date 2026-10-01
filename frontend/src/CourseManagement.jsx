import { api, apiError } from './api'
import confirmAction from './confirm'
import { useState, useEffect, useCallback } from 'react'
import axios from 'axios'
import toast from 'react-hot-toast'
import Dialog from './Dialog'
import { Book, Plus, Loader2, Edit, Trash2 } from 'lucide-react'



export default function CourseManagement({ appMode }) {
    const isAdmin = appMode.role === 'ADMIN'

    const [courses, setCourses] = useState([])
    const [isLoading, setIsLoading] = useState(true)

    const [isModalOpen, setIsModalOpen] = useState(false)
    const [isEditModalOpen, setIsEditModalOpen] = useState(false)

    const [newCourse, setNewCourse] = useState({ name: '', term: '', instructor: '' })
    const [editCourse, setEditCourse] = useState({ id: null, name: '', term: '', instructor: '' })

    // isLoading starts as true, so the initial load does not need to set it.
    const loadCourses = useCallback(() => axios.get(api.courses)
        .then(res => setCourses(res.data))
        .catch((error) => toast.error(apiError(error, 'Failed to load courses.')))
        .finally(() => setIsLoading(false)), [])

    const fetchData = () => {
        setIsLoading(true)
        return loadCourses()
    }

    useEffect(() => { loadCourses() }, [loadCourses])

    const handleCreate = async (e) => {
        e.preventDefault()
        try {
            await axios.post(api.adminCourses, newCourse)
            toast.success('Course added.')
            setIsModalOpen(false)
            setNewCourse({ name: '', term: '', instructor: '' })
            fetchData()
        } catch (error) { toast.error(apiError(error, 'Error occurred.')) }
    }

    const handleDelete = async (id) => {
        if(!await confirmAction(`Delete course '${courses.find(course => course.id === id)?.name || id}'?`)) return;
        try {
            await axios.delete(api.course(id))
            toast.success('Course deleted.')
            fetchData()
        } catch (error) { toast.error(apiError(error, 'Failed to delete course.')) }
    }

    const openEditModal = (course) => {
        setEditCourse({ id: course.id, name: course.name, term: course.term, instructor: course.instructor || '' })
        setIsEditModalOpen(true)
    }

    const handleUpdate = async (e) => {
        e.preventDefault()
        try {
            await axios.put(api.course(editCourse.id), { name: editCourse.name, term: editCourse.term, instructor: editCourse.instructor })
            toast.success('Course updated.')
            setIsEditModalOpen(false)
            fetchData()
        } catch (error) { toast.error(apiError(error, 'Failed to update course.')) }
    }

    return (
        <div className="card">
            <div className="detail-header">
                <div>
                    <h2>Courses</h2>
                    <p className="text-gray">Catalogue of active courses</p>
                </div>
                {isAdmin && (
                    <button className="btn-primary" onClick={() => setIsModalOpen(true)}>
                        <Plus size={18} /> Add course
                    </button>
                )}
            </div>

            <div className="table-responsive">{isLoading ? <div className="empty-state"><Loader2 className="spin"/></div> : <table><thead><tr><th>Course</th><th>Term</th><th>Instructor</th><th>Actions</th></tr></thead><tbody>{courses.map(c => <tr key={c.id}><td>{c.name}</td><td>{c.term}</td><td>{c.instructor || 'Not assigned'}</td><td>{isAdmin && <div className="row-actions"><button className="btn-secondary" onClick={() => openEditModal(c)}><Edit size={16}/>Edit</button><button className="btn-secondary" onClick={() => handleDelete(c.id)}><Trash2 size={16}/>Delete</button></div>}</td></tr>)}{courses.length === 0 && <tr><td colSpan="4"><div className="empty-state"><Book size={24}/><h4>No courses yet.</h4><p>Add the first course or run a CSV import.</p></div></td></tr>}</tbody></table>}</div>

            {isModalOpen && (
                <div className="modal-overlay">
                    <Dialog className="modal-content">
                        <h3>Add course</h3>
                        <form onSubmit={handleCreate}>
                            <div className="form-group"><label htmlFor="coursemanagement-field-1">Course name (required)</label><input id="coursemanagement-field-1" required type="text" value={newCourse.name} onChange={e => setNewCourse({...newCourse, name: e.target.value})}  aria-label="Course name"/></div>
                            <div className="form-group"><label htmlFor="coursemanagement-field-2">Term (required)</label><input id="coursemanagement-field-2" required type="text" value={newCourse.term} onChange={e => setNewCourse({...newCourse, term: e.target.value})}  aria-label="Term"/></div>
                                <div className="form-group"><label htmlFor="coursemanagement-field-3">Instructor</label><input id="coursemanagement-field-3" type="text" placeholder="e.g. Ayberk Arda" value={newCourse.instructor} onChange={e => setNewCourse({...newCourse, instructor: e.target.value})}  aria-label="Instructor"/></div>
                            <div className="modal-actions"><button type="button" className="btn-secondary" onClick={() => setIsModalOpen(false)}>Cancel</button><button type="submit" className="btn-primary">Save</button></div>
                        </form>
                    </Dialog>
                </div>
            )}

            {isEditModalOpen && (
                <div className="modal-overlay">
                    <Dialog className="modal-content">
                        <h3>Edit course</h3>
                        <form onSubmit={handleUpdate}>
                            <div className="form-group"><label htmlFor="coursemanagement-field-4">Course name (required)</label><input id="coursemanagement-field-4" required type="text" value={editCourse.name} onChange={e => setEditCourse({...editCourse, name: e.target.value})}  aria-label="Course name"/></div>
                            <div className="form-group"><label htmlFor="coursemanagement-field-5">Term (required)</label><input id="coursemanagement-field-5" required type="text" value={editCourse.term} onChange={e => setEditCourse({...editCourse, term: e.target.value})}  aria-label="Term"/></div>
                            <div className="form-group"><label htmlFor="coursemanagement-field-6">Instructor</label><input id="coursemanagement-field-6" type="text" value={editCourse.instructor} onChange={e => setEditCourse({...editCourse, instructor: e.target.value})}  aria-label="Instructor"/></div>
                            <div className="modal-actions"><button type="button" className="btn-secondary" onClick={() => setIsEditModalOpen(false)}>Cancel</button><button type="submit" className="btn-primary">Save changes</button></div>
                        </form>
                    </Dialog>
                </div>
            )}
        </div>
    )
}