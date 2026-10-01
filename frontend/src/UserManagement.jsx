import { api, apiError } from './api'
import { useState, useEffect, useCallback, useRef } from 'react'
import axios from 'axios'
import { Search, Loader2 } from 'lucide-react'
import toast from 'react-hot-toast'
import confirmAction from './confirm'
import { useDebounce } from './hooks/useDebounce'



export default function UserManagement() {
    const [users, setUsers] = useState([])
    const [searchTerm, setSearchTerm] = useState('')
    const [isLoading, setIsLoading] = useState(true)

    const debouncedSearchTerm = useDebounce(searchTerm, 500)

    // Show the loader as soon as the search changes (state adjusted during render).
    const [loadedSearch, setLoadedSearch] = useState(debouncedSearchTerm)
    if (loadedSearch !== debouncedSearchTerm) {
        setLoadedSearch(debouncedSearchTerm)
        setIsLoading(true)
    }

    // Only the most recent request may update the list (see StudentList).
    const latestRequestId = useRef(0)
    const loadUsers = useCallback((search) => {
        const requestId = ++latestRequestId.current
        const isLatest = () => requestId === latestRequestId.current
        return axios.get(api.accounts, { params: { search, page: 0, size: 50, deleted: false } })
            .then(response => { if (isLatest()) setUsers(response.data.content) })
            .catch((error) => { if (isLatest()) toast.error(apiError(error, 'Failed to load users.')) })
            .finally(() => { if (isLatest()) setIsLoading(false) })
    }, [])

    const fetchUsers = (search) => {
        setIsLoading(true)
        return loadUsers(search)
    }

    useEffect(() => {
        loadUsers(debouncedSearchTerm)
    }, [debouncedSearchTerm, loadUsers])

    const handleRoleChange = async (userId, newRole) => {
        const user = users.find(item => item.id === userId)
        const userName = user ? `${user.firstName} ${user.lastName}` : `user ${userId}`
        const roleLabel = newRole === 'ADMIN' ? 'Administrator' : 'User'
        if (!await confirmAction(`Change the role of ${userName} to ${roleLabel}?`, { confirmLabel: 'Change role', destructive: false })) return
        try {
            await axios.put(api.accountRole(userId), { role: newRole })
            toast.success('User role updated.')
            fetchUsers(debouncedSearchTerm)
        } catch (error) {
            toast.error(apiError(error, 'Error occurred while updating role.'))
        }
    }

    return (
        <div className="card">
            <div className="detail-header split-row">
                <div>
                    <h2>Users</h2>
                    <p className="text-gray">Roles and access levels</p>
                </div>

                <div className="search-box">
                    <Search size={20} />
                    <input
                        type="text"
                        placeholder="Search users..."
                        value={searchTerm}
                        onChange={(e) => setSearchTerm(e.target.value)}
                     aria-label="Search by name or number"/>
                </div>
            </div>

            <div className="table-responsive">
                {isLoading ? (
                    <div className="empty-state"><Loader2 className="spin text-gray" size={32} /><p>Loading...</p></div>
                ) : users.length === 0 ? (
                    <div className="empty-state"><p>No users found.</p></div>
                ) : (
                    <table>
                        <thead>
                        <tr>
                            <th>User</th>
                            <th>Identifier</th>
                            <th>Role</th>
                            <th>Action</th>
                        </tr>
                        </thead>
                        <tbody>
                        {users.map((user) => (
                            <tr key={user.id}>
                                <td>{user.firstName} {user.lastName}</td>
                                <td className="mono">{user.studentNumber || 'Not assigned'}</td>
                                <td>
                                    <span className={user.role === 'ADMIN' ? 'badge' : 'badge neutral'}>
                                      {user.role === 'ADMIN' ? 'Administrator' : 'User'}
                                    </span>
                                </td>
                                <td>
                                    <select aria-label={`Role for ${user.firstName} ${user.lastName}`}
                                        value={user.role} 
                                        onChange={(e) => handleRoleChange(user.id, e.target.value)}
                                    >
                                        <option value="USER">User</option>
                                        <option value="ADMIN">Administrator</option>
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