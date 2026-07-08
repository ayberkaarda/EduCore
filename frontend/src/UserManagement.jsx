import { useState, useEffect } from 'react'
import axios from 'axios'
import { Search, ShieldAlert, Loader2 } from 'lucide-react'
import toast, { Toaster } from 'react-hot-toast'
import { useDebounce } from './hooks/useDebounce'

const API_BASE = 'http://localhost:8080/api/v1'

export default function UserManagement() {
    const [users, setUsers] = useState([])
    const [searchTerm, setSearchTerm] = useState('')
    const [isLoading, setIsLoading] = useState(true)

    const debouncedSearchTerm = useDebounce(searchTerm, 500)

    useEffect(() => {
        fetchUsers(debouncedSearchTerm)
    }, [debouncedSearchTerm])

    const fetchUsers = async (search) => {
        setIsLoading(true)
        try {
            const response = await axios.get(`${API_BASE}/accounts?search=${search}&page=0&size=50`)
            setUsers(response.data.content)
        } catch (error) {
            toast.error('Failed to load users.')
        } finally {
            setIsLoading(false)
        }
    }

    const handleRoleChange = async (userId, newRole) => {
        try {
            await axios.put(`${API_BASE}/accounts/${userId}/role`, { role: newRole })
            toast.success('User role updated!')
            fetchUsers(debouncedSearchTerm)
        } catch (error) {
            toast.error('Error occurred while updating role.')
        }
    }

    return (
        <div className="card">
            <Toaster />
            <div className="detail-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                    <h2><ShieldAlert size={24} style={{ display: 'inline', verticalAlign: 'middle', marginRight: '8px', color: '#ef4444' }} /> Role Management</h2>
                    <p className="text-gray">Manage access levels of users in the system.</p>
                </div>

                <div className="search-box" style={{ position: 'relative', width: '300px', maxWidth: '100%', flexShrink: 0 }}>
                    <Search size={20} style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)', color: '#6b7280' }} />
                    <input
                        type="text"
                        placeholder="Search users..."
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
            </div>

            <div className="table-responsive">
                {isLoading ? (
                    <div className="empty-state"><Loader2 className="spin text-gray" size={32} /><p>Loading...</p></div>
                ) : users.length === 0 ? (
                    <div className="empty-state"><p>No users found.</p></div>
                ) : (
                    <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                        <thead>
                        <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>User</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>ID</th>
                            <th style={{ padding: '1rem', color: '#6b7280' }}>Current Role</th>
                            <th style={{ padding: '1rem', textAlign: 'right', color: '#6b7280' }}>Action</th>
                        </tr>
                        </thead>
                        <tbody>
                        {users.map((user) => (
                            <tr key={user.id} style={{ borderBottom: '1px solid #e5e7eb' }}>
                                <td style={{ padding: '1rem', fontWeight: '500' }}>{user.firstName} {user.lastName}</td>
                                <td style={{ padding: '1rem', color: '#6b7280' }}>{user.studentNumber || 'N/A'}</td>
                                <td style={{ padding: '1rem' }}>
                    <span className={`badge ${user.role === 'ACADEMICIAN' ? 'success' : ''}`} style={{ backgroundColor: user.role === 'ACADEMICIAN' ? '#fee2e2' : '#d1fae5', color: user.role === 'ACADEMICIAN' ? '#991b1b' : '#065f46' }}>
                      {user.role === 'ACADEMICIAN' ? 'Academician' : 'Student'}
                    </span>
                                </td>
                                <td style={{ padding: '1rem', textAlign: 'right' }}>
                                    <select
                                        value={user.role}
                                        onChange={(e) => handleRoleChange(user.id, e.target.value)}
                                        style={{ padding: '0.4rem', borderRadius: '0.375rem', border: '1px solid #d1d5db', outline: 'none', cursor: 'pointer' }}
                                    >
                                        <option value="STUDENT"> Student</option>
                                        <option value="ACADEMICIAN"> Academician</option>
                                    </select>
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    )
}