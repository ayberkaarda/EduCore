import { useState } from 'react'
import { Pencil, Trash2 } from 'lucide-react'
import confirmAction from '../../../components/confirm'
import PageControls from '../../../components/PageControls'
import { toastApiError } from '../../../lib/error-messages'
import { useDeleteIpRule, useIpRules, useSaveIpRule } from '../hooks'
import type { IpDenyRule } from '../api'
import IpRuleFormDialog from './IpRuleFormDialog'

export default function IpRulesScreen() {
  const [page, setPage] = useState(0)
  const [editing, setEditing] = useState<IpDenyRule | 'new' | null>(null)
  const query = useIpRules(page)
  const save = useSaveIpRule()
  const remove = useDeleteIpRule()
  const deleteRule = async (rule: IpDenyRule) => {
    if (!await confirmAction(`Delete deny rule ${rule.value}?`)) return
    try {
      await remove.mutateAsync(rule.id)
    } catch (error) {
      toastApiError(error, 'Could not delete the deny rule.')
    }
  }

  return (
    <div>
      <div className="detail-header">
        <div>
          <h2>IP rules</h2>
          <p className="text-gray">Request-level IPv4 deny rules, newest first</p>
        </div>
        <button className="btn-primary" onClick={() => setEditing('new')}>Add deny rule</button>
      </div>
      {query.isPending ? (
        <p role="status">Loading deny rules</p>
      ) : query.isError ? (
        <div role="alert">
          Could not load deny rules.{' '}
          <button className="btn-secondary" onClick={() => void query.refetch()}>Retry</button>
        </div>
      ) : (
        <div className="table-responsive">
          <table className="admin-rules-table">
            <colgroup>
              <col className="admin-col-9" />
              <col className="admin-col-17" />
              <col className="admin-col-9" />
              <col className="admin-col-18" />
              <col className="admin-col-13" />
              <col className="admin-col-13" />
              <col className="admin-col-11" />
              <col className="admin-col-10" />
            </colgroup>
            <thead>
              <tr>
                <th>Kind</th>
                <th>Value</th>
                <th>Source</th>
                <th>Reason</th>
                <th>Expiry</th>
                <th>Created</th>
                <th>Created by</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {query.data.content.map(rule => (
                <tr key={rule.id}>
                  <td><span className="badge">{rule.kind}</span></td>
                  <td className="mono" title={rule.value}>{rule.value}</td>
                  <td>
                    <span className={rule.source === 'AUTO' ? 'badge warning' : 'badge neutral'}>
                      {rule.source}
                    </span>
                  </td>
                  <td title={rule.reason || 'None'}>{rule.reason || 'None'}</td>
                  <td>{rule.expiresAt ? new Date(rule.expiresAt).toLocaleString() : 'Permanent'}</td>
                  <td>{new Date(rule.createdAt).toLocaleString()}</td>
                  <td
                    className="mono"
                    title={rule.createdBy == null ? 'Created by the system' : `Account id: ${rule.createdBy}`}
                  >
                    {rule.createdBy == null ? 'System' : `#${rule.createdBy}`}
                  </td>
                  <td>
                    <div className="compact-table-actions" role="group" aria-label={`Actions for ${rule.value}`}>
                      <button
                        type="button"
                        className="btn-secondary table-icon-action"
                        title="Edit"
                        onClick={() => setEditing(rule)}
                        aria-label={`Edit ${rule.value}`}
                      >
                        <Pencil size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="btn-secondary table-icon-action danger-text"
                        title="Delete"
                        disabled={remove.isPending}
                        onClick={() => void deleteRule(rule)}
                        aria-label={`Delete ${rule.value}`}
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {query.data.content.length === 0 && (
                <tr>
                  <td colSpan={8} className="empty-state">No deny rules yet. Add an address, range or subnet.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
      <PageControls data={query.data} page={page} onPage={setPage} pending={query.isFetching} />
      {editing && (
        <IpRuleFormDialog
          rule={editing === 'new' ? undefined : editing}
          onCancel={() => setEditing(null)}
          onSubmit={async body => {
            await save.mutateAsync({ body, id: editing === 'new' ? undefined : editing.id })
            setEditing(null)
          }}
        />
      )}
    </div>
  )
}
