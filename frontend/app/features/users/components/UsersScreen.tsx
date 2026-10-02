import { Search } from 'lucide-react'
import { useEffect, useState } from 'react'
import confirmAction from '../../../components/confirm'
import { LoadingState } from '../../../components/Feedback'
import { useDebounce } from '../../../components/useDebounce'
import { toastApiError } from '../../../lib/error-messages'
import { toast } from '../../../lib/toast'
import type { Account, Role } from '../../../lib/types'
import { useAccounts, useChangeRole } from '../hooks'
import AccountLifecycleActions, { AccountStatusBadge } from '../../profile-lifecycle/components/AccountLifecycleActions'

const roleLabel = (role: Role) => (role === 'ADMIN' ? 'Administrator' : 'User')

/** ADMIN: roles and access levels (GET /admin/accounts, PUT /admin/accounts/{id}/role). */
export default function UsersScreen() {
  const [searchTerm, setSearchTerm] = useState('')
  const search = useDebounce(searchTerm, 500)
  const [page, setPage] = useState(0)
  const [showDeleted, setShowDeleted] = useState(false)
  const [lastSearch, setLastSearch] = useState(search)
  if (lastSearch !== search) {
    setLastSearch(search)
    setPage(0)
  }
  const accounts = useAccounts({ search, page, size: 20, deleted: showDeleted })
  const totalPages = accounts.data?.totalPages ?? 0
  if (accounts.isSuccess && !accounts.isFetching && page > Math.max(0, totalPages - 1)) {
    setPage(Math.max(0, totalPages - 1))
  }
  const changeRole = useChangeRole()

  useEffect(() => {
    if (accounts.error) toastApiError(accounts.error, 'Failed to load users.')
  }, [accounts.error])

  const handleRoleChange = async (user: Account, role: Role) => {
    const name = [user.firstName, user.lastName].filter(Boolean).join(' ')
    if (!await confirmAction(`Change the role of ${name} to ${roleLabel(role)}?`, { confirmLabel: 'Change role', destructive: false })) return
    try {
      await changeRole.mutateAsync({ accountId: user.id, role })
      toast.success('User role updated.')
    } catch (error) {
      toastApiError(error, 'Error occurred while updating role.')
    }
  }

  const users = accounts.data?.content ?? []
  return (
    <div className="card">
      <div className="detail-header split-row">
        <div>
          <h2>Users</h2>
          <p className="text-gray">Roles and access levels</p>
        </div>
        <div className="search-box">
          <Search size={20} aria-hidden="true" />
          <input type="text" placeholder="Search users..." value={searchTerm} onChange={event => setSearchTerm(event.target.value)} aria-label="Search users" maxLength={100} />
        </div>
      </div>

      <p className="text-gray">{accounts.data?.totalElements ?? 0} users</p>
      <button type="button" className="btn-secondary" aria-pressed={showDeleted} onClick={() => { setShowDeleted(!showDeleted); setPage(0) }}>{showDeleted ? 'Show active' : 'Show deleted'}</button>
      <div className="table-responsive">
        {accounts.isPending ? <LoadingState /> : users.length === 0 ? (
          <div className="empty-state"><p>No users found.</p></div>
        ) : (
          <table>
            <thead><tr><th>User</th><th>Identifier</th><th>Role</th><th>Action</th></tr></thead>
            <tbody>
              {users.map(user => (
                <tr key={user.id}>
                  <td>{user.firstName} {user.lastName} <AccountStatusBadge account={user} /></td>
                  <td className="mono">{user.studentNumber || 'Not assigned'}</td>
                  <td><span className={user.role === 'ADMIN' ? 'badge' : 'badge neutral'}>{roleLabel(user.role)}</span></td>
                  <td>
                    <select aria-label={`Role for ${[user.firstName, user.lastName].filter(Boolean).join(' ')}`} value={user.role}
                      disabled={changeRole.isPending || showDeleted} onChange={event => void handleRoleChange(user, event.target.value as Role)}>
                      <option value="USER">User</option>
                      <option value="ADMIN">Administrator</option>
                    </select>
                    <AccountLifecycleActions account={user} softDelete />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {(totalPages > 1 || page > 0) && <div className="split-row">
        <button type="button" className="btn-secondary" disabled={page === 0} onClick={() => setPage(previous => Math.max(0, previous - 1))}>Previous</button>
        <span>Page {page + 1} of {Math.max(1, totalPages)}</span>
        <button type="button" className="btn-secondary" disabled={page >= totalPages - 1} onClick={() => setPage(previous => previous + 1)}>Next</button>
      </div>}
    </div>
  )
}
