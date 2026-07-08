import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import axios from 'axios'
import { Search, ChevronRight, User, Loader2, Plus } from 'lucide-react'
import toast, { Toaster } from 'react-hot-toast'
import { useDebounce } from './hooks/useDebounce'

const API_BASE = 'http://localhost:8080/api/v1'

export default function StudentList() {
    const [students, setStudents] = useState([])
    const [searchTerm, setSearchTerm] = useState('')
    const [isLoading, setIsLoading] = useState(true)
    const [isModalOpen, setIsModalOpen] = useState(false)
    const [newStudent, setNewStudent] = useState({ firstName: '', lastName: '' })

    const debouncedSearchTerm = useDebounce(searchTerm, 500)
    const navigate = useNavigate()

    useEffect(() => {
        fetchStudents(debouncedSearchTerm)
    }, [debouncedSearchTerm])

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

    const handleCreateStudent = async (e) => {
        e.preventDefault()
        try {
            await axios.post(`${API_BASE}/accounts/student`, newStudent)
            toast.success('Student successfully added! ID assigned automatically.')
            setIsModalOpen(false)
            setNewStudent({ firstName: '', lastName: '' })
            fetchStudents(debouncedSearchTerm)
        } catch (error) {
            toast.error('An error occurred.')
        }
    }

    return (
        <div className="card">
            <Toaster />
            <div className="detail-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                    <h2>Student Management</h2>
                    <p className="text-gray">Select a student from the list to assign courses.</p>
                </div>

                <div className="search-box" style={{ position: 'relative', width: '300px', maxWidth: '100%', flexShrink: 0 }}>
                    <Search size={20} style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)', color: '#6b7280' }} />
                    <input
                        type="text"
                        placeholder="Search by First or Last Name..."
                        value={searchTerm}
                        onChange={(e) => setSearchTerm(e.target.value)}
                        style={{
                            width: '100%',
                            padding: '0.6rem 1rem 0.6rem 2.5rem',
                            borderRadius: '0.5rem',
                            border: '1px solid #e5e7eb',
                            outline: 'none',
                            boxSizing: 'border-box'
                        }}
                    />
                </div>

                <button className="btn-primary" onClick={() => setIsModalOpen(true)}>
                    <Plus size={18} /> New Student
                </button>
            </div>

            <div className="table-responsive" style={{marginTop: '1.5rem'}}>
                {isLoading ? (
                    <div className="empty-state"><Loader2 className="spin text-gray" size={32} /><p>Loading...</p></div>
                ) : students.length === 0 ? (
                    <div className="empty-state"><p>No students found.</p></div>
                ) : (
                    <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                        <thead>
                        <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Student ID</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Full Name</th>
                            <th style={{ padding: '1rem', textAlign: 'right', color: '#6b7280' }}>Actions</th>
                        </tr>
                        </thead>
                        <tbody>
                        {students.map((student) => (
                            <tr key={student.id} style={{ borderBottom: '1px solid #e5e7eb', transition: 'background 0.2s' }}>
                                <td style={{ padding: '1rem', fontWeight: '500' }}>#{student.studentNumber || 'N/A'}</td>
                                <td style={{ padding: '1rem' }}>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                                        <div style={{ backgroundColor: '#f3f4f6', padding: '0.5rem', borderRadius: '50%' }}><User size={18} color="#4f46e5"/></div>
                                        {student.firstName} {student.lastName}
                                    </div>
                                </td>
                                <td style={{ padding: '1rem', textAlign: 'right' }}>
                                    <button
                                        className="btn-primary"
                                        onClick={() => navigate(`/students/${student.id}`)}
                                        style={{ padding: '0.5rem 1rem', fontSize: '0.875rem' }}
                                    >
                                        Course Selection <ChevronRight size={16} />
                                    </button>
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                )}
            </div>

            {isModalOpen && (
                <div className="modal-overlay">
                    <div className="modal-content" style={{ padding: '2.5rem' }}>
                        <h3 style={{ marginTop: 0, marginBottom: '0.5rem', color: '#111827' }}>Add New Student</h3>
                        <p className="text-gray" style={{ fontSize: '0.85rem', marginBottom: '1.5rem', lineHeight: '1.4' }}>
                            Student ID will be assigned automatically by the system.
                        </p>
                        <form onSubmit={handleCreateStudent}>
                            <div style={{ marginBottom: '1.2rem' }}>
                                <label style={{ display: 'block', marginBottom: '0.4rem', fontWeight: 500, fontSize: '0.9rem' }}>First Name</label>
                                <input required type="text" value={newStudent.firstName} onChange={(e) => setNewStudent({ ...newStudent, firstName: e.target.value })} style={{ width: '100%', padding: '0.75rem', borderRadius: '0.5rem', border: '1px solid #d1d5db', outline: 'none', boxSizing: 'border-box' }} />
                            </div>
                            <div style={{ marginBottom: '2rem' }}>
                                <label style={{ display: 'block', marginBottom: '0.4rem', fontWeight: 500, fontSize: '0.9rem' }}>Last Name</label>
                                <input required type="text" value={newStudent.lastName} onChange={(e) => setNewStudent({ ...newStudent, lastName: e.target.value })} style={{ width: '100%', padding: '0.75rem', borderRadius: '0.5rem', border: '1px solid #d1d5db', outline: 'none', boxSizing: 'border-box' }} />
                            </div>
                            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem' }}>
                                <button type="button" className="btn-secondary" onClick={() => setIsModalOpen(false)}>Cancel</button>
                                <button type="submit" className="btn-primary">Save</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    )
}