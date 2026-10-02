import { useState } from 'react'
import { Link } from 'react-router'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import confirmAction from '../../../components/confirm'
import Dialog from '../../../components/Dialog'
import TextField from '../../../components/TextField'
import PageControls from '../../../components/PageControls'
import { describeApiError, toastApiError } from '../../../lib/error-messages'
import type { JobLog } from '../../../lib/types'
import { useDeleteJobLogs, useJobLogs, useJobEntries } from '../hooks'
import type { JobLogFilters } from '../api'
import { jobLogFilterSchema, type JobLogFilterValues } from '../schemas'
import { downloadLogs } from '../log-details'
function StatusBadge({ status }: { status: JobLog['status'] }) {
  const style = status === 'SUCCEEDED' ? 'success' : status === 'PARTIAL' ? 'warning' : status === 'FAILED' ? 'danger' : 'neutral'
  return <span className={`badge ${style}`}>{status === 'SUCCEEDED' ? 'Success' : status === 'PARTIAL' ? 'Partial' : status === 'FAILED' ? 'Failed' : 'Running'}</span>
}
function EntriesDialog({ log, onClose }: { log: JobLog; onClose: () => void }) {
  const [page, setPage] = useState(0)
  const query = useJobEntries(log.id, page)
  return <div className="modal-overlay log-overlay"><Dialog className="modal-content"><h3>Execution details: <span className="mono">{log.fileName}</span></h3><StatusBadge status={log.status} /><p>{log.reason}</p>
    {query.isPending ? <p role="status">Loading entries</p> : query.isError ? <div role="alert">{describeApiError(query.error, 'Could not load entries.')}<button className="btn-secondary" onClick={() => void query.refetch()}>Retry</button></div> : <div className="table-responsive"><table><thead><tr><th>Row</th><th>Level</th><th>Reason</th><th>Masked record</th></tr></thead><tbody>{query.data.content.map(entry => <tr key={entry.id}><td className="mono">{entry.rowNumber ?? 'File'}</td><td><span className={`badge ${entry.level === 'ERROR' ? 'danger' : entry.level === 'WARN' ? 'warning' : 'neutral'}`}>{entry.level}</span></td><td>{entry.reason}</td><td className="mono">{entry.rawMasked ?? 'None'}</td></tr>)}{query.data.content.length === 0 && <tr><td colSpan={4}>No entries recorded.</td></tr>}</tbody></table></div>}
    <PageControls data={query.data} page={page} onPage={setPage} pending={query.isFetching} /><div className="modal-actions"><button className="btn-secondary" onClick={onClose}>Close details</button></div>
  </Dialog></div>
}
export default function JobLogsScreen() {
  const [filters, setFilters] = useState<JobLogFilters>({ page: 0, size: 20 })
  const [selectedIds, setSelectedIds] = useState<number[]>([])
  const [selectedLog, setSelectedLog] = useState<JobLog | null>(null)
  const query = useJobLogs(filters)
  const remove = useDeleteJobLogs()
  const { register, handleSubmit, formState: { errors } } = useForm<JobLogFilterValues>({ resolver: zodResolver(jobLogFilterSchema), defaultValues: { status: '', from: '', to: '', file: '', size: '20' } })
  const logs = query.data?.content ?? []
  const selected = selectedIds.filter(id => logs.some(log => log.id === id))
  const apply = handleSubmit(values => { setFilters({ status: values.status || undefined, from: values.from || undefined, to: values.to || undefined, file: values.file || undefined, size: Number(values.size), page: 0 }); setSelectedIds([]) })
  const deleteSelected = async () => {
    if (!selected.length || !await confirmAction(`Delete ${selected.length} selected job logs?`)) return
    try { await remove.mutateAsync(selected); setSelectedIds([]) } catch (error) { toastApiError(error, 'Could not delete job logs.') }
  }
  return <div><div className="detail-header"><div><h2>Job logs</h2><p className="text-gray">Student and course import history</p></div><Link className="btn-primary" to="/app/imports">Import CSV</Link></div>
    <form onSubmit={apply} noValidate className="inline-fields">
      <div className="form-group"><label htmlFor="log-status">Status</label><select id="log-status" {...register('status')}><option value="">All statuses</option><option value="SUCCEEDED">Success</option><option value="PARTIAL">Partial</option><option value="FAILED">Failed</option></select></div>
      <TextField id="log-from" label="From" registration={register('from')} error={errors.from?.message} hint="ISO date-time with offset" />
      <TextField id="log-to" label="To" registration={register('to')} error={errors.to?.message} />
      <TextField id="log-file" label="File" registration={register('file')} error={errors.file?.message} />
      <div className="form-group"><label htmlFor="log-size">Page size</label><select id="log-size" {...register('size')}><option>20</option><option>50</option><option>100</option></select></div><button className="btn-secondary">Apply filters</button>
    </form>
    {selected.length > 0 && <div className="selection-bar"><span>{selected.length} selected</span><button className="btn-secondary" onClick={() => downloadLogs(logs.filter(log => selected.includes(log.id)))}>Download JSON</button><button className="btn-secondary" disabled={remove.isPending} onClick={() => void deleteSelected()}>Delete selected logs</button></div>}
    {query.isPending ? <p role="status">Loading job logs</p> : query.isError ? <div role="alert">{describeApiError(query.error, 'Could not load job logs.')}<button className="btn-secondary" onClick={() => void query.refetch()}>Retry</button></div> : <div className="table-responsive"><table><thead><tr><th><input type="checkbox" aria-label="Select all logs" checked={logs.length > 0 && selected.length === logs.length} onChange={event => setSelectedIds(event.target.checked ? logs.map(log => log.id) : [])} /></th><th>File</th><th>Entity</th><th>Started</th><th>Succeeded</th><th>Failed</th><th>Status</th><th>Actions</th></tr></thead><tbody>{logs.map(log => <tr key={log.id}><td><input type="checkbox" aria-label={`Select log ${log.fileName}`} checked={selected.includes(log.id)} onChange={() => setSelectedIds(selected.includes(log.id) ? selected.filter(id => id !== log.id) : [...selected, log.id])} /></td><td className="mono">{log.fileName}</td><td><span className="badge neutral">{log.entityType}</span></td><td className="mono">{new Date(log.startedAt ?? log.createdAt).toLocaleString()}</td><td className="numeric">{log.successfulRecords}</td><td className={log.failedRecords > 0 ? 'numeric danger-text' : 'numeric'}>{log.failedRecords}</td><td><StatusBadge status={log.status} /></td><td><button className="btn-secondary" onClick={() => setSelectedLog(log)}>Details</button></td></tr>)}{logs.length === 0 && <tr><td colSpan={8} className="empty-state">No job logs match these filters.</td></tr>}</tbody></table></div>}
    <PageControls data={query.data} page={filters.page} pending={query.isFetching} onPage={page => { setFilters({ ...filters, page }); setSelectedIds([]) }} />
    {selectedLog && <EntriesDialog key={selectedLog.id} log={selectedLog} onClose={() => setSelectedLog(null)} />}
  </div>
}
