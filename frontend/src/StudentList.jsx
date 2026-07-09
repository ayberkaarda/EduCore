import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import axios from 'axios'
import { Search, ChevronRight, User, Loader2, Plus, Edit, Trash2 } from 'lucide-react'
import toast, { Toaster } from 'react-hot-toast'
import { useDebounce } from './hooks/useDebounce'

const API_BASE = 'http://localhost:8080/api/v1'

export default function StudentList({ appMode }) {
    const isAdmin = appMode.role === 'ADMIN' // YETKİ KONTROLÜ

    const [students, setStudents] = useState([])
    const [searchTerm, setSearchTerm] = useState('')
    const [isLoading, setIsLoading] = useState(true)

    // Modallar
    const [isModalOpen, setIsModalOpen] = useState(false)
    const [isEditModalOpen, setIsEditModalOpen] = useState(false)

    // Form Verileri
    const [newStudent, setNewStudent] = useState({ firstName: '', lastName: '' })
    const [editStudent, setEditStudent] = useState({ id: null, firstName: '', lastName: '' })

    const debouncedSearchTerm = useDebounce(searchTerm, 500)
    const navigate = useNavigate()

    useEffect(() => { fetchStudents(debouncedSearchTerm) }, [debouncedSearchTerm])

    const fetchStudents = async (search) => {
        setIsLoading(true)
        try {
            const response = await axios.get(`${API_BASE}/accounts/students?search=${search}&page=0&size=50`)
            setStudents(response.data.content)
        } catch (error) {
            toast.error("Failed to load students")
        } finally {
            setIsLoading(false)
        }
    }

    // --- YENİ VERİ İŞLEMLERİ ---
    const handleCreateStudent = async (e) => {
        e.preventDefault()
        try {
            await axios.post(`${API_BASE}/accounts/student`, newStudent)
            toast.success('Student successfully added!')
            setIsModalOpen(false)
            setNewStudent({ firstName: '', lastName: '' })
            fetchStudents(debouncedSearchTerm)
        } catch (error) { toast.error('Error occurred.') }
    }

    const handleDelete = async (id) => {
        if(!window.confirm('Are you sure you want to delete this student?')) return;
        try {
            await axios.delete(`${API_BASE}/accounts/${id}`)
            toast.success('Student deleted successfully!')
            fetchStudents(debouncedSearchTerm)
        } catch (error) { toast.error('Failed to delete student.') }
    }

    const openEditModal = (student) => {
        setEditStudent({ id: student.id, firstName: student.firstName, lastName: student.lastName })
        setIsEditModalOpen(true)
    }

    const handleUpdateStudent = async (e) => {
        e.preventDefault()
        try {
            await axios.put(`${API_BASE}/accounts/${editStudent.id}`, { firstName: editStudent.firstName, lastName: editStudent.lastName })
            toast.success('Student updated successfully!')
            setIsEditModalOpen(false)
            fetchStudents(debouncedSearchTerm)
        } catch (error) { toast.error('Failed to update student.') }
    }

    return (
        <div className="card">
            <Toaster />
            <div className="detail-header">
                <div>
                    <h2>Student Management</h2>
                    <p className="text-gray">Select a student from the list to assign courses.</p>
                </div>

                <div className="search-box" style={{ position: 'relative', width: '300px' }}>
                    <Search size={20} style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)', color: '#6b7280' }} />
                    <input type="text" placeholder="Search..." value={searchTerm} onChange={(e) => setSearchTerm(e.target.value)} style={{ width: '100%', padding: '0.6rem 1rem 0.6rem 2.5rem', borderRadius: '0.5rem', border: '1px solid #e5e7eb', outline: 'none', boxSizing: 'border-box' }} />
                </div>

                {/* SADECE ADMIN GÖREBİLİR */}
                {isAdmin && (
                    <button className="btn-primary" onClick={() => setIsModalOpen(true)}>
                        <Plus size={18} /> New Student
                    </button>
                )}
            </div>

            <div className="table-responsive" style={{marginTop: '1.5rem'}}>
                {isLoading ? ( <div className="empty-state"><Loader2 className="spin text-gray" size={32} /></div> )
                    : students.length === 0 ? ( <div className="empty-state"><p>No students found.</p></div> )
                        : (
                            <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                                <thead>
                                <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
                                    <th style={{ padding: '1rem', color: '#6b7280' }}>ID</th>
                                    <th style={{ padding: '1rem', color: '#6b7280' }}>Full Name</th>
                                    <th style={{ padding: '1rem', textAlign: 'right', color: '#6b7280' }}>Actions</th>
                                </tr>
                                </thead>
                                <tbody>
                                {students.map((student) => (
                                    <tr key={student.id} style={{ borderBottom: '1px solid #e5e7eb' }}>
                                        <td style={{ padding: '1rem', fontWeight: '500' }}>#{student.studentNumber || 'N/A'}</td>
                                        <td style={{ padding: '1rem' }}>
                                            <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                                                <div style={{ backgroundColor: '#f3f4f6', width: '36px', height: '36px', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><User size={18} color="#4f46e5"/></div>
                                                {student.firstName} {student.lastName}
                                            </div>
                                        </td>
                                        <td style={{ padding: '1rem', textAlign: 'right' }}>
                                            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
                                                <button className="btn-secondary" onClick={() => navigate(`/students/${student.id}`)} style={{ padding: '0.4rem 0.8rem' }}>
                                                    Courses <ChevronRight size={16} />
                                                </button>

                                                {/* SADECE ADMIN SİL/DÜZENLE GÖREBİLİR */}
                                                {isAdmin && (
                                                    <>
                                                        <button className="btn-secondary" onClick={() => openEditModal(student)} style={{ padding: '0.4rem', backgroundColor: '#fef3c7', color: '#b45309', border: 'none' }} title="Edit">
                                                            <Edit size={16} />
                                                        </button>
                                                        <button className="btn-secondary" onClick={() => handleDelete(student.id)} style={{ padding: '0.4rem', backgroundColor: '#fee2e2', color: '#b91c1c', border: 'none' }} title="Delete">
                                                            <Trash2 size={16} />
                                                        </button>
                                                    </>
                                                )}
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                                </tbody>
                            </table>
                        )}
            </div>

            {/* --- ADD MODAL --- */}
            {isModalOpen && (
                <div className="modal-overlay">
                    <div className="modal-content">
                        <h3 style={{ marginTop: 0 }}>Add New Student</h3>
                        <form onSubmit={handleCreateStudent}>
                            <div className="form-group"><label>First Name</label><input required type="text" value={newStudent.firstName} onChange={e => setNewStudent({...newStudent, firstName: e.target.value})} /></div>
                            <div className="form-group"><label>Last Name</label><input required type="text" value={newStudent.lastName} onChange={e => setNewStudent({...newStudent, lastName: e.target.value})} /></div>
                            <div className="modal-actions"><button type="button" className="btn-secondary" onClick={() => setIsModalOpen(false)}>Cancel</button><button type="submit" className="btn-primary">Save</button></div>
                        </form>
                    </div>
                </div>
            )}

            {/* --- EDIT MODAL --- */}
            {isEditModalOpen && (
                <div className="modal-overlay">
                    <div className="modal-content">
                        <h3 style={{ marginTop: 0 }}>Edit Student</h3>
                        <form onSubmit={handleUpdateStudent}>
                            <div className="form-group"><label>First Name</label><input required type="text" value={editStudent.firstName} onChange={e => setEditStudent({...editStudent, firstName: e.target.value})} /></div>
                            <div className="form-group"><label>Last Name</label><input required type="text" value={editStudent.lastName} onChange={e => setEditStudent({...editStudent, lastName: e.target.value})} /></div>
                            <div className="modal-actions"><button type="button" className="btn-secondary" onClick={() => setIsEditModalOpen(false)}>Cancel</button><button type="submit" className="btn-primary" style={{backgroundColor: '#f59e0b'}}>Update</button></div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    )
}