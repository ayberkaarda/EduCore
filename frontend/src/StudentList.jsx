import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import axios from 'axios'
import { Search, ChevronRight, User, Loader2, Plus, Edit, Trash2, Network } from 'lucide-react'
import toast, { Toaster } from 'react-hot-toast'
import { useDebounce } from './hooks/useDebounce'

const API_BASE = 'http://localhost:8080/api/v1'

export default function StudentList({ appMode }) {
    const isAdmin = appMode.role === 'ADMIN'

    const [students, setStudents] = useState([])
    const [searchTerm, setSearchTerm] = useState('')
    const [isLoading, setIsLoading] = useState(true)
    const [availableIps, setAvailableIps] = useState([]);
    const [ipInputMode, setIpInputMode] = useState('manual');
    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] = useState(1)
    const pageSize = 8

    const [isModalOpen, setIsModalOpen] = useState(false)
    const [isEditModalOpen, setIsEditModalOpen] = useState(false)

    const [newStudent, setNewStudent] = useState({ firstName: '', lastName: '', studentNumber: '' })
    const [editStudent, setEditStudent] = useState({ id: null, firstName: '', lastName: '', studentNumber: '', ipAddress: '' })

    const debouncedSearchTerm = useDebounce(searchTerm, 500)
    const navigate = useNavigate()

    useEffect(() => { setPage(0) }, [debouncedSearchTerm])
    useEffect(() => { fetchStudents(debouncedSearchTerm, page) }, [debouncedSearchTerm, page])

    // Edit modalı açıldığında IP havuzunu Backend'den çek
    useEffect(() => {
        if (isEditModalOpen) {
            const fetchAvailableIps = async () => {
                try {
                    const response = await axios.get(`${API_BASE}/ips`);
                    setAvailableIps(response.data);
                } catch (error) {
                    console.error("IP listesi çekilemedi:", error);
                    toast.error("IP listesi yüklenemedi.");
                }
            };

            fetchAvailableIps();
            setIpInputMode('manual'); // Her modal açılışında varsayılan olarak manual veya mevcut IP gelsin
        }
    }, [isEditModalOpen]);

    const fetchStudents = async (search, currentPage) => {
        setIsLoading(true)
        try {
            const response = await axios.get(`${API_BASE}/accounts/students?search=${search}&page=${currentPage}&size=${pageSize}`)
            setStudents(response.data.content)
            setTotalPages(response.data.totalPages)
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
            toast.success('Student successfully added!')
            setIsModalOpen(false)
            setNewStudent({ firstName: '', lastName: '', studentNumber: '' })
            fetchStudents(debouncedSearchTerm, page)
        } catch (error) { toast.error('Error occurred.') }
    }

    const handleDelete = async (id) => {
        if(!window.confirm('Are you sure you want to delete this student?')) return;
        try {
            await axios.delete(`${API_BASE}/accounts/${id}`)
            toast.success('Student deleted successfully!')
            fetchStudents(debouncedSearchTerm, page)
        } catch (error) { toast.error('Failed to delete student.') }
    }

    const openEditModal = (student) => {
        setEditStudent({
            id: student.id,
            firstName: student.firstName,
            lastName: student.lastName,
            studentNumber: student.studentNumber || '',
            ipAddress: student.ipAddress || ''
        })
        setIsEditModalOpen(true)
    }

    const handleUpdateStudent = async (e) => {
        e.preventDefault()
        console.log("Gönderilen Veri:", editStudent);
        try {
            await axios.put(`${API_BASE}/accounts/${editStudent.id}`, {
                firstName: editStudent.firstName,
                lastName: editStudent.lastName,
                studentNumber: editStudent.studentNumber,
                ipAddress: editStudent.ipAddress
            })
            toast.success('Student updated successfully!')
            setIsEditModalOpen(false)
            fetchStudents(debouncedSearchTerm, page)
        } catch (error) {
            toast.error(error.response?.data?.error || 'Failed to update student.')
        }
    }

    return (
        <div className="card">
            <Toaster />
            <div className="detail-header">
                <div>
                    <h2>Student Management</h2>
                    <p className="text-gray">Select a student from the list to view courses or assign IP.</p>
                </div>

                <div className="search-box" style={{ position: 'relative', width: '300px' }}>
                    <Search size={20} style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)', color: '#6b7280' }} />
                    <input type="text" placeholder="Search..." value={searchTerm} onChange={(e) => setSearchTerm(e.target.value)} style={{ width: '100%', padding: '0.6rem 1rem 0.6rem 2.5rem', borderRadius: '0.5rem', border: '1px solid #e5e7eb', outline: 'none', boxSizing: 'border-box' }} />
                </div>

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
                            <>
                                <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                                    <thead>
                                    <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
                                        <th style={{ padding: '1rem', color: '#6b7280' }}>ID</th>
                                        <th style={{ padding: '1rem', color: '#6b7280' }}>Full Name</th>
                                        <th style={{ padding: '1rem', color: '#6b7280' }}>IP Address</th>
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
                                            <td style={{ padding: '1rem' }}>
                                                {student.ipAddress ? (
                                                    <span className="badge" style={{ backgroundColor: '#eff6ff', color: '#1e40af', display: 'inline-flex', alignItems: 'center', gap: '4px' }}><Network size={14} /> {student.ipAddress}</span>
                                                ) : (
                                                    <span className="text-gray" style={{ fontSize: '0.85rem' }}>Unassigned</span>
                                                )}
                                            </td>
                                            <td style={{ padding: '1rem', textAlign: 'right' }}>
                                                <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
                                                    <button className="btn-secondary" onClick={() => navigate(`/students/${student.id}`)} style={{ padding: '0.4rem 0.8rem' }}>
                                                        Courses <ChevronRight size={16} />
                                                    </button>

                                                    {isAdmin && (
                                                        <>
                                                            <button className="btn-secondary" onClick={() => openEditModal(student)} style={{ padding: '0.4rem', backgroundColor: '#fef3c7', color: '#b45309', border: 'none' }} title="Edit"><Edit size={16} /></button>
                                                            <button className="btn-secondary" onClick={() => handleDelete(student.id)} style={{ padding: '0.4rem', backgroundColor: '#fee2e2', color: '#b91c1c', border: 'none' }} title="Delete"><Trash2 size={16} /></button>
                                                        </>
                                                    )}
                                                </div>
                                            </td>
                                        </tr>
                                    ))}
                                    </tbody>
                                </table>

                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '1.5rem', padding: '0 1rem' }}>
                                    <button className="btn-secondary" onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0} style={{ padding: '0.4rem 1rem' }}>Previous</button>
                                    <span className="text-gray" style={{ fontSize: '0.875rem' }}>Page {page + 1} of {totalPages === 0 ? 1 : totalPages}</span>
                                    <button className="btn-secondary" onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1} style={{ padding: '0.4rem 1rem' }}>Next</button>
                                </div>
                            </>
                        )}
            </div>

            {isModalOpen && (
                <div className="modal-overlay">
                    <div className="modal-content">
                        <h3 style={{ marginTop: 0 }}>Add New Student</h3>
                        <form onSubmit={handleCreateStudent}>
                            <div className="form-group"><label>First Name</label><input required type="text" value={newStudent.firstName} onChange={e => setNewStudent({...newStudent, firstName: e.target.value})} /></div>
                            <div className="form-group"><label>Last Name</label><input required type="text" value={newStudent.lastName} onChange={e => setNewStudent({...newStudent, lastName: e.target.value})} /></div>
                            <div className="form-group"><label>Student ID</label><input required type="text" placeholder="e.g. 2601005" value={newStudent.studentNumber} onChange={e => setNewStudent({...newStudent, studentNumber: e.target.value})} /></div>
                            <div className="modal-actions"><button type="button" className="btn-secondary" onClick={() => setIsModalOpen(false)}>Cancel</button><button type="submit" className="btn-primary">Save</button></div>
                        </form>
                    </div>
                </div>
            )}

            {isEditModalOpen && (
                <div className="modal-overlay">
                    <div className="modal-content">
                        <h3 style={{ marginTop: 0 }}>Edit Student</h3>
                        <form onSubmit={handleUpdateStudent}>
                            <div className="form-group"><label>First Name</label><input required type="text" value={editStudent.firstName} onChange={e => setEditStudent({...editStudent, firstName: e.target.value})} /></div>
                            <div className="form-group"><label>Last Name</label><input required type="text" value={editStudent.lastName} onChange={e => setEditStudent({...editStudent, lastName: e.target.value})} /></div>
                            <div className="form-group"><label>Student ID</label><input required type="text" value={editStudent.studentNumber} onChange={e => setEditStudent({...editStudent, studentNumber: e.target.value})} /></div>

                            <div className="form-group">
                                <label>Assigned IP Address (Optional)</label>

                                <select
                                    style={{ marginBottom: '10px', width: '100%', padding: '0.6rem 1rem', borderRadius: '0.5rem', border: '1px solid var(--border-color)' }}
                                    value={ipInputMode === 'manual' ? 'manual' : (editStudent.ipAddress || 'manual')}
                                    onChange={(e) => {
                                        if (e.target.value === 'manual') {
                                            setIpInputMode('manual');
                                        } else {
                                            setIpInputMode('select');
                                            setEditStudent({ ...editStudent, ipAddress: e.target.value });
                                        }
                                    }}
                                >
                                    <option value="manual">-- Yeni IP Gir (Manuel) --</option>
                                    {availableIps.map((ipObj) => {
                                        const ipValue = ipObj.definition;

                                        // IP statikse ve listedeki başka bir öğrenci tarafından kullanılıyorsa kilitliyoruz
                                        const isUsedByAnother = ipObj.type === 'STATIC' && students.some(
                                            (s) => s.ipAddress === ipValue && s.id !== editStudent.id
                                        );

                                        return (
                                            <option
                                                key={ipObj.id}
                                                value={ipValue}
                                                disabled={isUsedByAnother}
                                                style={{ color: isUsedByAnother ? '#ef4444' : 'inherit' }}
                                            >
                                                {ipValue} ({ipObj.type === 'STATIC' ? 'Statik' : 'CIDR Bloğu'}) {isUsedByAnother ? ' - 🔴 (Dolu)' : ''}
                                            </option>
                                        );
                                    })}
                                </select>

                                {ipInputMode === 'manual' && (
                                    <input
                                        type="text"
                                        value={editStudent.ipAddress || ''}
                                        onChange={(e) => setEditStudent({ ...editStudent, ipAddress: e.target.value })}
                                        placeholder="Örn: 192.168.1.5"
                                        style={{ width: '100%', padding: '0.6rem 1rem', borderRadius: '0.5rem', border: '1px solid var(--border-color)', outline: 'none', boxSizing: 'border-box' }}
                                    />
                                )}
                            </div>
                            <div className="modal-actions"><button type="button" className="btn-secondary" onClick={() => setIsEditModalOpen(false)}>Cancel</button><button type="submit" className="btn-primary" style={{backgroundColor: '#f59e0b'}}>Update</button></div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    )
}