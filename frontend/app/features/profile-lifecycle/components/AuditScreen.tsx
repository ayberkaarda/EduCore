import { useState } from 'react'
import { AccessDenied, LoadingState } from '../../../components/Feedback'
import PageControls from '../../../components/PageControls'
import { describeApiError } from '../../../lib/error-messages'
import { useAuth } from '../../auth/auth-context'
import { useSecurityEvents } from '../hooks'
import { formatAccountDate } from '../../../lib/date-format'
export function meta() { return [{ title: 'Security events · EduCore' }] }
export default function AuditScreen() {
  const { user } = useAuth()
  return user?.role === 'ADMIN' ? <AdminAudit /> : <AccessDenied />
}
function summarize(details: Record<string, unknown> | null) {
  if (!details) return '—'
  const text = Object.entries(details).map(([key, value]) => `${key}: ${typeof value === 'object' ? JSON.stringify(value) : String(value)}`).join('; ')
  return text.length > 240 ? `${text.slice(0, 237)}…` : text || '—'
}
function AdminAudit() {
  const [page, setPage] = useState(0)
  const events = useSecurityEvents(page)
  return <section className="card"><h2>Security events</h2><p className="text-gray">Account activity and security audit trail</p>
    {events.isPending ? <LoadingState /> : events.isError ? <div role="alert"><p>{describeApiError(events.error, 'Could not load security events.')}</p><button type="button" className="btn-secondary" onClick={() => void events.refetch()}>Retry</button></div> : <>
      {events.data.content.length === 0 ? <p>No security events.</p> : <div className="table-responsive"><table><thead><tr><th>Type</th><th>Actor</th><th>Target</th><th>IP address</th><th>Request ID</th><th>Time</th><th>Details</th></tr></thead><tbody>
      {events.data.content.map(event => <tr key={event.id}><td>{event.type}</td><td className="mono">{event.actorAccountId ?? event.actorPseudonym ?? '—'}</td><td className="mono">{event.targetAccountId ?? event.targetPseudonym ?? '—'}</td><td className="mono">{event.ip ?? '—'}</td><td className="mono">{event.requestId ?? '—'}</td><td><time dateTime={event.at}>{formatAccountDate(event.at)}</time></td><td>{summarize(event.details)}</td></tr>)}
      </tbody></table></div>}
    </>}
    <PageControls data={events.data} page={page} onPage={setPage} pending={events.isFetching} />
  </section>
}
