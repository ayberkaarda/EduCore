import { api, apiError } from './api'
import confirmAction from './confirm'
import { useState, useEffect, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import axios from 'axios'
import { Search, ChevronRight, User, Loader2, Plus, Edit, Trash2, ArrowUpDown } from 'lucide-react'
import toast from 'react-hot-toast'
import Dialog from './Dialog'
import TemporaryPasswordDialog from './TemporaryPasswordDialog'
import { useDebounce } from './hooks/useDebounce'



export default function StudentList({ appMode }) {
    const isAdmin = appMode.role === 'ADMIN'

    const [students, setStudents] = useState([])
    const [searchTerm, setSearchTerm] = useState('')
    const [isLoading, setIsLoading] = useState(true)
    const [availableIps, setAvailableIps] = useState([]);
    const [poolHint, setPoolHint] = useState('')
    const [ipInputMode, setIpInputMode] = useState('manual');
    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] = useState(1)
    const [sortDirection, setSortDirection] = useState('asc')

    // Silinenleri göstermek için yeni state
    const [showDeleted, setShowDeleted] = useState(false);

    const pageSize = 8

    const [isModalOpen, setIsModalOpen] = useState(false)
    const [isEditModalOpen, setIsEditModalOpen] = useState(false)
    const [createdStudent, setCreatedStudent] = useState(null)
    const [creationAnnouncement, setCreationAnnouncement] = useState('')
    const [isCreating, setIsCreating] = useState(false)

    const [newStudent, setNewStudent] = useState({ firstName: '', lastName: '', studentNumber: '' })
    const [editStudent, setEditStudent] = useState({ id: null, firstName: '', lastName: '', studentNumber: '', ipAddress: '' })

    const debouncedSearchTerm = useDebounce(searchTerm, 500)
    const navigate = useNavigate()

    // A new search goes back to the first page (state adjusted during render).
    const [lastSearch, setLastSearch] = useState(debouncedSearchTerm)
    if (lastSearch !== debouncedSearchTerm) {
        setLastSearch(debouncedSearchTerm)
        setPage(0)
    }

    // Show the loader as soon as the query changes.
    const queryKey = `${debouncedSearchTerm}|${page}|${sortDirection}|${showDeleted}`
    const [loadedQueryKey, setLoadedQueryKey] = useState(queryKey)
    if (loadedQueryKey !== queryKey) {
        setLoadedQueryKey(queryKey)
        setIsLoading(true)
    }

    // Only the most recent request may update the list, so a slow older
    // response cannot overwrite newer results or end the loading state early.
    const latestRequestId = useRef(0)
    const loadStudents = useCallback((search, currentPage, direction, isDeletedView) => {
        const requestId = ++latestRequestId.current
        const isLatest = () => requestId === latestRequestId.current
        return axios.get(api.students, { params: { search, page: currentPage, size: pageSize, direction, deleted: isDeletedView } })
            .then(response => {
                if (!isLatest()) return
                setStudents(response.data.content)
                setTotalPages(response.data.totalPages)
            })
            .catch((error) => { if (isLatest()) toast.error(apiError(error, "Failed to load students")) })
            .finally(() => { if (isLatest()) setIsLoading(false) })
    }, [])

    const fetchStudents = (search, currentPage, direction, isDeletedView) => {
        setIsLoading(true)
        return loadStudents(search, currentPage, direction, isDeletedView)
    }

    useEffect(() => {
        loadStudents(debouncedSearchTerm, page, sortDirection, showDeleted)
    }, [debouncedSearchTerm, page, sortDirection, showDeleted, loadStudents])

    useEffect(() => {
        if (isEditModalOpen) {
            const fetchAvailableIps = async () => {
                try {
                    const response = await axios.get(api.ipRules);
                    setAvailableIps(response.data);
                } catch (error) {
                    toast.error(apiError(error, "Could not load IP rules."));
                }
            };
            fetchAvailableIps();
        }
    }, [isEditModalOpen]);


    const handleCreateStudent = async (e) => {
        e.preventDefault()
        if (isCreating) return
        setIsCreating(true)
        try {
            const response = await axios.post(api.students, newStudent)
            const student = {
                firstName: response.data.firstName ?? newStudent.firstName,
                lastName: response.data.lastName ?? newStudent.lastName,
                studentNumber: response.data.studentNumber ?? newStudent.studentNumber,
                username: response.data.username,
                temporaryPassword: response.data.temporaryPassword,
            }
            setCreatedStudent(student)
            setCreationAnnouncement(`Student created: ${student.firstName} ${student.lastName}, student number ${student.studentNumber}. Save the temporary password shown in the dialog.`)
            toast.success('Student added.')
            setIsModalOpen(false)
            setNewStudent({ firstName: '', lastName: '', studentNumber: '' })
            fetchStudents(debouncedSearchTerm, page, sortDirection, showDeleted)
        } catch (error) {
            toast.error(apiError(error, 'Error occurred.'))
        } finally {
            setIsCreating(false)
        }
    }

    const handleDelete = async (id) => {
        if(!await confirmAction(`Delete student ${students.find(student => student.id === id)?.studentNumber || id}? This hides the record.`)) return;
        try {
            await axios.delete(api.account(id))
            toast.success('Student deleted.')
            fetchStudents(debouncedSearchTerm, page, sortDirection, showDeleted)
        } catch (error) { toast.error(apiError(error, 'Failed to delete student.')) }
    }

    const openEditModal = (student) => {
        setEditStudent({
            id: student.id,
            firstName: student.firstName,
            lastName: student.lastName,
            studentNumber: student.studentNumber || '',
            ipAddress: student.ipAddress || ''
        })
        setIpInputMode('manual')
        setIsEditModalOpen(true)
    }

    const handleUpdateStudent = async (e) => {
        e.preventDefault()
        try {
            await axios.put(api.account(editStudent.id), {
                firstName: editStudent.firstName,
                lastName: editStudent.lastName,
                studentNumber: editStudent.studentNumber,
                ipAddress: editStudent.ipAddress
            })
            toast.success('Student updated.')
            setIsEditModalOpen(false)
            fetchStudents(debouncedSearchTerm, page, sortDirection, showDeleted)
        } catch (error) {
            toast.error(apiError(error, 'Failed to update student.'))
        }
    }

    const toggleSorting = () => {
        setSortDirection(prev => (prev === 'asc' ? 'desc' : 'asc'))
    }

    return (
        <div className="card">
            <span className="temporary-password-announcement" role="status" aria-live="polite" aria-atomic="true">{creationAnnouncement}</span>
            {createdStudent && (
                <TemporaryPasswordDialog student={createdStudent} onSaved={() => setCreatedStudent(null)} />
            )}
            <div className="detail-header">
                <div>
                    <h2>Students</h2>
                    <p className="text-gray">Search, edit and enrol students</p>
                </div>

                <div className="search-box">
                    <Search size={20} />
                    <input type="text" placeholder="Search by name or number" value={searchTerm} onChange={(e) => setSearchTerm(e.target.value)}  aria-label="Search by name or number"/>
                </div>

                <div className="inline-fields">
                    {isAdmin && (
                        <button
                            className="btn-secondary"
                            aria-pressed={showDeleted}
                            onClick={() => {
                                setShowDeleted(!showDeleted);
                                setPage(0);
                            }}
                        >
                            {showDeleted ? "Show active" : "Show deleted"}
                        </button>
                    )}
                    {isAdmin && (
                        <button className="btn-primary" onClick={() => setIsModalOpen(true)}>
                            <Plus size={18} /> Add student
                        </button>
                    )}
                </div>
            </div>

            <div className="table-responsive">
                {isLoading ? ( <div className="empty-state"><Loader2 className="spin text-gray" size={32} /></div> )
                    : students.length === 0 ? ( <div className="empty-state"><User size={24}/><h4>{debouncedSearchTerm ? 'No students match your search.' : showDeleted ? 'No deleted students.' : 'No students yet.'}</h4><p>{debouncedSearchTerm ? 'Try another name or number.' : 'Add a student or run a CSV import.'}</p>{debouncedSearchTerm && <button className="btn-secondary" onClick={() => setSearchTerm('')}>Clear search</button>}</div> )
                        : (
                            <>
                                <table>
                                    <thead>
                                    <tr>
                                        <th>Student number</th>
                                        <th aria-sort={sortDirection === 'asc' ? 'ascending' : 'descending'}><button className="sort-button" onClick={toggleSorting}>Name <ArrowUpDown size={14}/></button></th>
                                        <th>IP address</th>
                                        <th>Actions</th>
                                    </tr>
                                    </thead>
                                    <tbody>
                                    {students.map((student) => (
                                        <tr key={student.id} className={showDeleted ? 'deleted-row' : ''}>
                                            <td>#{student.studentNumber || 'N/A'}</td>
                                            <td>
                                                <div className="inline-group">
                                                    <span className="avatar">{student.firstName?.slice(0,1)}{student.lastName?.slice(0,1)}</span>
                                                    {student.firstName} {student.lastName}
                                                    {showDeleted && (
                                                        <span className="badge neutral">Deleted</span>
                                                    )}
                                                </div>
                                            </td>
                                            <td>
                                                {student.ipAddress ? (
                                                    <span className="mono">{student.ipAddress}</span>
                                                ) : (
                                                    <span className="text-gray">Not assigned</span>
                                                )}
                                            </td>
                                            <td>
                                                <div className="row-actions">
                                                    <button className="btn-secondary" disabled={showDeleted} onClick={() => navigate(`/students/${student.id}`)}>
                                                        Courses <ChevronRight size={16} />
                                                    </button>

                                                    {isAdmin && !showDeleted && (
                                                        <>
                                                            <button className="btn-secondary" onClick={() => openEditModal(student)} title="Edit" aria-label="Edit"><Edit size={16}/>Edit</button>
                                                            <button className="btn-secondary" onClick={() => handleDelete(student.id)} title="Delete" aria-label="Delete"><Trash2 size={16}/>Delete</button>
                                                        </>
                                                    )}
                                                </div>
                                            </td>
                                        </tr>
                                    ))}
                                    </tbody>
                                </table>

                                <div className="split-row">
                                    <button className="btn-secondary" onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}>Previous</button>
                                    <span className="text-gray">Page {page + 1} of {totalPages === 0 ? 1 : totalPages}</span>
                                    <button className="btn-secondary" onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1}>Next</button>
                                </div>
                            </>
                        )}
            </div>

            
            {isModalOpen && (
                <div className="modal-overlay">
                    <Dialog className="modal-content">
                        <h3>Add student</h3>
                        <form onSubmit={handleCreateStudent}>
                            <div className="form-group"><label htmlFor="studentlist-field-1">First name (required)</label><input id="studentlist-field-1" required type="text" value={newStudent.firstName} onChange={e => setNewStudent({...newStudent, firstName: e.target.value})}  aria-label="First name"/></div>
                            <div className="form-group"><label htmlFor="studentlist-field-2">Last name (required)</label><input id="studentlist-field-2" required type="text" value={newStudent.lastName} onChange={e => setNewStudent({...newStudent, lastName: e.target.value})}  aria-label="Last name"/></div>
                            <div className="form-group"><label htmlFor="studentlist-field-3">Student number (required)</label><input id="studentlist-field-3" required type="text" placeholder="e.g. 2601005" value={newStudent.studentNumber} onChange={e => setNewStudent({...newStudent, studentNumber: e.target.value})}  aria-label="Student number"/></div>
                            <div className="modal-actions"><button type="button" className="btn-secondary" disabled={isCreating} onClick={() => setIsModalOpen(false)}>Cancel</button><button type="submit" className="btn-primary" disabled={isCreating}>{isCreating ? 'Saving…' : 'Save'}</button></div>
                        </form>
                    </Dialog>
                </div>
            )}

            {isEditModalOpen && (
                <div className="modal-overlay">
                    <Dialog className="modal-content">
                        <h3>Edit student</h3>
                        <form onSubmit={handleUpdateStudent}>
                            <div className="form-group"><label htmlFor="studentlist-field-4">First name (required)</label><input id="studentlist-field-4" required type="text" value={editStudent.firstName} onChange={e => setEditStudent({...editStudent, firstName: e.target.value})}  aria-label="First name"/></div>
                            <div className="form-group"><label htmlFor="studentlist-field-5">Last name (required)</label><input id="studentlist-field-5" required type="text" value={editStudent.lastName} onChange={e => setEditStudent({...editStudent, lastName: e.target.value})}  aria-label="Last name"/></div>
                            <div className="form-group"><label htmlFor="studentlist-field-6">Student number (required)</label><input id="studentlist-field-6" required type="text" value={editStudent.studentNumber} onChange={e => setEditStudent({...editStudent, studentNumber: e.target.value})}  aria-label="Student number"/></div>

                            <div className="form-group">
                                <label htmlFor="studentlist-field-7">IP source</label>
                                <select id="studentlist-field-7" aria-label="IP source"
                                    value={ipInputMode === 'manual' ? 'manual' : (editStudent.ipAddress || 'manual')}
                                    onChange={(e) => {
                                        const selectedValue = e.target.value;
                                        setPoolHint('');
                                        if (selectedValue === 'manual') {
                                            setIpInputMode('manual');
                                            setEditStudent({ ...editStudent, ipAddress: '' });
                                        } else {
                                            const selectedIpObj = availableIps.find(ipObj => {
                                                const val = ipObj.originalValue;
                                                return val === selectedValue;
                                            });

                                            if (selectedIpObj && selectedIpObj.type === 'STATIC') {
                                                setIpInputMode('select');
                                                setEditStudent({ ...editStudent, ipAddress: selectedValue });
                                            } else {
                                                setIpInputMode('manual');
                                                const prefix = selectedValue.split(/[-/]/)[0];
                                                setEditStudent({ ...editStudent, ipAddress: prefix });
                                                setPoolHint('Enter a single address from the selected pool.');
                                            }
                                        }
                                    }}
                                >
                                    <option value="manual">Manual</option>
                                    {availableIps.map((ipObj) => {
                                        const ipValue = ipObj.originalValue;
                                        const isUsedByAnother = students.some(
                                            (s) => s.ipAddress === ipValue && s.id !== editStudent.id
                                        );
                                        const isLocked = ipObj.type === 'STATIC' && isUsedByAnother;

                                        return (
                                            <option
                                                key={ipObj.id}
                                                value={ipValue}
                                                disabled={isLocked}
                                            >
                                                {ipValue} ({ipObj.type === 'STATIC' ? 'Single address' : 'Pool'}) {ipObj.type === 'STATIC' ? (isLocked ? ' — Assigned' : ' — Available') : ''}
                                            </option>
                                        );
                                    })}
                                </select>

                                {poolHint && <p className="field-hint">{poolHint}</p>}
                                {ipInputMode === 'manual' && (
                                    <input
                                        type="text"
                                        value={editStudent.ipAddress || ''}
                                        onChange={(e) => setEditStudent({ ...editStudent, ipAddress: e.target.value })}
                                        placeholder="e.g. 192.168.1.5"
                                     aria-label="IP address"/>
                                )}
                            </div>
                            <div className="modal-actions"><button type="button" className="btn-secondary" onClick={() => setIsEditModalOpen(false)}>Cancel</button><button type="submit" className="btn-primary">Save changes</button></div>
                        </form>
                    </Dialog>
                </div>
            )}
        </div>
    )
}
