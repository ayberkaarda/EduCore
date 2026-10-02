import { useId, useState } from 'react'
import Dialog from '../../../components/Dialog'
import confirmAction from '../../../components/confirm'
import { describeApiError } from '../../../lib/error-messages'
import { toast } from '../../../lib/toast'
import type { Account } from '../../../lib/types'
import { deleteStudent } from '../../students/api'
import { useQueryClient } from '@tanstack/react-query'
import { useAccountLifecycle } from '../hooks'
import { formatAccountDate } from '../../../lib/date-format'
import UnlockLoginButton from './UnlockLoginButton'
export function AccountStatusBadge({ account }: { account: Account }) {
  const status = account.status ?? 'ACTIVE'
  return <span className="badge neutral">{status === 'ACTIVE' ? 'Active' : status === 'DEACTIVATED' ? 'Deactivated' : 'Pending deletion'}
    {status === 'PENDING_DELETION' && account.deleteAfter && <> · <time dateTime={account.deleteAfter}>{formatAccountDate(account.deleteAfter)}</time></>}
  </span>
}
export default function AccountLifecycleActions({ account, softDelete = false }: { account: Account; softDelete?: boolean }) {
  const { restore, purge } = useAccountLifecycle()
  const client = useQueryClient()
  const [open, setOpen] = useState(false)
  const [error, setError] = useState('')
  const [deleting, setDeleting] = useState(false)
  const restoreRow = async () => {
    setError('')
    try { await restore.mutateAsync(account.id); toast.success('Account restored.') }
    catch (failure) { setError(describeApiError(failure, 'Could not restore the account.')) }
  }
  const deactivate = async () => {
    if (!await confirmAction(`Delete ${account.username}? This deactivates the account; an administrator can restore it.`)) return
    setDeleting(true); setError('')
    try { await deleteStudent(account.id); await client.invalidateQueries(); toast.success('Account deactivated.') }
    catch (failure) { setError(describeApiError(failure, 'Could not delete the account.')) }
    finally { setDeleting(false) }
  }
  return <>
    <UnlockLoginButton accountId={account.id} />
    {(account.status === 'DEACTIVATED' || account.status === 'PENDING_DELETION') && <button type="button" className="btn-secondary" disabled={restore.isPending} onClick={() => void restoreRow()}>Restore</button>}
    {softDelete && (account.status ?? 'ACTIVE') === 'ACTIVE' && <button type="button" className="btn-secondary" disabled={deleting} onClick={() => void deactivate()}>Delete</button>}
    <button type="button" className="btn-secondary" onClick={() => setOpen(true)}>Delete permanently</button>
    {error && <p role="alert" className="inline-error">{error}</p>}
    {open && <HardDeleteDialog account={account} busy={purge.isPending} onKeep={() => setOpen(false)} onDelete={async () => { await purge.mutateAsync(account); setOpen(false); toast.success('Account permanently deleted.') }} />}
  </>
}
function HardDeleteDialog({ account, busy, onKeep, onDelete }: { account: Account; busy: boolean; onKeep: () => void; onDelete: () => Promise<void> }) {
  const id = useId()
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState('')
  return <div className="modal-overlay"><Dialog className="modal-content"><h3>Delete permanently</h3>
    <p>Permanently delete {account.username}? Their profile and enrolments will be erased immediately. This cannot be undone.</p>
    <form onSubmit={event => { event.preventDefault(); if (confirmation !== account.username || busy) return; setError(''); void onDelete().catch(failure => setError(describeApiError(failure, 'Could not permanently delete the account.'))) }}>
      <div className="form-group"><label htmlFor={id}>Type username {account.username} to confirm</label>
      <input id={id} autoComplete="off" maxLength={255} value={confirmation} onChange={event => setConfirmation(event.target.value)} /></div>
      {error && <p role="alert" className="inline-error">{error}</p>}
      <div className="modal-actions"><button type="button" className="btn-secondary" disabled={busy} onClick={onKeep}>Keep account</button>
      <button type="submit" className="btn-primary btn-danger" disabled={busy || confirmation !== account.username}>Delete permanently</button></div>
    </form></Dialog></div>
}
