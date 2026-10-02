import { Plus, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import confirmAction from '../../../components/confirm'
import { LoadingState } from '../../../components/Feedback'
import { toastApiError } from '../../../lib/error-messages'
import { toast } from '../../../lib/toast'
import type { IpAllocation, IpRuleType } from '../../../lib/types'
import { useCreateIpAllocation, useDeleteIpAllocation, useIpAllocations } from '../hooks'
import IpAllocationFormDialog from './IpAllocationFormDialog'

const TYPE_LABELS: Record<IpRuleType, string> = { STATIC: 'Single address', RANGE: 'Range', CIDR: 'Subnet' }

/** ADMIN: allowed IPv4 addresses, ranges and subnets (GET/POST/DELETE /admin/ip-allocations). */
export default function IpAllocationsScreen() {
  const rules = useIpAllocations()
  const createRule = useCreateIpAllocation()
  const deleteRule = useDeleteIpAllocation()
  const [isAddOpen, setIsAddOpen] = useState(false)

  useEffect(() => {
    if (rules.error) toastApiError(rules.error, 'Could not load IP allocations.')
  }, [rules.error])

  const handleDelete = async (rule: IpAllocation) => {
    if (!await confirmAction(`Delete IP allocation ${rule.originalValue}?`)) return
    try {
      await deleteRule.mutateAsync(rule.id)
      toast.success('IP allocation deleted.')
    } catch (error) {
      toastApiError(error, 'Delete failed.')
    }
  }

  const list = rules.data ?? []
  return (
    <div>
      <div className="detail-header">
        <div>
          <h2>IP allocations</h2>
          <p className="text-gray">Allowed IPv4 addresses, ranges and subnets</p>
        </div>
        <button type="button" className="btn-primary" onClick={() => setIsAddOpen(true)}><Plus size={16} /> Add rule</button>
      </div>

      <div className="table-responsive">
        {rules.isPending ? <LoadingState /> : rules.isError ? <div role="alert">Could not load IP allocations. <button type="button" className="btn-secondary" onClick={() => void rules.refetch()}>Retry</button></div> : (
          <table>
            <thead><tr><th>Type</th><th>Definition</th><th>Actions</th></tr></thead>
            <tbody>
              {list.map(rule => (
                <tr key={rule.id}>
                  <td><span className="badge">{TYPE_LABELS[rule.type] ?? rule.type}</span></td>
                  <td className="mono">{rule.originalValue}</td>
                  <td>
                    <button type="button" className="btn-secondary" onClick={() => void handleDelete(rule)} aria-label={`Delete rule ${rule.originalValue}`} title="Delete"><Trash2 size={16} /></button>
                  </td>
                </tr>
              ))}
              {list.length === 0 && <tr><td colSpan={3}><div className="empty-state"><h4>No IP allocations yet.</h4><p>Add an address, range or subnet.</p></div></td></tr>}
            </tbody>
          </table>
        )}
      </div>

      {isAddOpen && (
        <IpAllocationFormDialog
          onCancel={() => setIsAddOpen(false)}
          onSubmit={async body => {
            await createRule.mutateAsync(body)
            toast.success('IP allocation added.')
            setIsAddOpen(false)
          }}
        />
      )}
    </div>
  )
}
