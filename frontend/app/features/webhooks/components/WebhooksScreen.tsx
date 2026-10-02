import { useState } from 'react'
import { History, Pencil, Play, Power, Trash2 } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import Dialog from '../../../components/Dialog'
import PageControls from '../../../components/PageControls'
import confirmAction from '../../../components/confirm'
import { describeApiError, toastApiError } from '../../../lib/error-messages'
import { toast } from '../../../lib/toast'
import { createWebhook, webhookKeys, type Webhook } from '../api'
import { useWebhooks, useDeleteWebhook, useUpdateWebhook, useTestWebhook, useDeliveries } from '../hooks'
import WebhookFormDialog from './WebhookFormDialog'
import WebhookSecretDialog from './WebhookSecretDialog'

function DeliveryDialog({ webhook, onClose }: { webhook: Webhook; onClose: () => void }) {
  const [page, setPage] = useState(0)
  const query = useDeliveries(webhook.id, page)
  return (
    <div className="modal-overlay log-overlay">
      <Dialog className="modal-content">
        <h3>Delivery history</h3>
        <p className="mono">{webhook.url}</p>
        {query.isPending ? (
          <p role="status">Loading deliveries</p>
        ) : query.isError ? (
          <div role="alert">
            {describeApiError(query.error, 'Could not load deliveries.')}
            <button className="btn-secondary" onClick={() => void query.refetch()}>Retry</button>
          </div>
        ) : (
          <div className="table-responsive">
            <table>
              <thead>
                <tr>
                  <th>Event</th>
                  <th>Status</th>
                  <th>Attempt</th>
                  <th>Response code</th>
                  <th>Last error</th>
                </tr>
              </thead>
              <tbody>
                {query.data.content.map(delivery => (
                  <tr key={delivery.id}>
                    <td>{delivery.event}</td>
                    <td>
                      <span className={`badge ${delivery.status === 'DELIVERED' ? 'success' : delivery.status === 'FAILED' ? 'danger' : 'neutral'}`}>
                        {delivery.status}
                      </span>
                    </td>
                    <td className="numeric">{delivery.attempt}</td>
                    <td className="mono">{delivery.responseCode ?? 'None'}</td>
                    <td>{delivery.lastError ?? 'None'}</td>
                  </tr>
                ))}
                {query.data.content.length === 0 && <tr><td colSpan={5}>No deliveries yet.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
        <PageControls data={query.data} page={page} onPage={setPage} pending={query.isFetching} />
        <div className="modal-actions">
          <button className="btn-secondary" onClick={onClose}>Close history</button>
        </div>
      </Dialog>
    </div>
  )
}

export default function WebhooksScreen() {
  const [editing, setEditing] = useState<Webhook | 'new' | null>(null)
  const [secret, setSecret] = useState<string | null>(null)
  const [history, setHistory] = useState<Webhook | null>(null)
  const client = useQueryClient()
  const query = useWebhooks()
  const update = useUpdateWebhook()
  const remove = useDeleteWebhook()
  const test = useTestWebhook()
  const toggle = async (webhook: Webhook) => {
    try {
      await update.mutateAsync({
        id: webhook.id,
        body: { url: webhook.url, events: webhook.events, active: !webhook.active },
      })
    } catch (error) {
      toastApiError(error, 'Could not change the webhook.')
    }
  }
  const deleteSubscription = async (webhook: Webhook) => {
    if (!await confirmAction(`Delete webhook ${webhook.url}? Its delivery history will also be deleted.`)) return
    try {
      await remove.mutateAsync(webhook.id)
    } catch (error) {
      toastApiError(error, 'Could not delete the webhook.')
    }
  }
  const sendTest = async (id: number) => {
    try {
      await test.mutateAsync(id)
      toast.success('Test event queued.')
    } catch (error) {
      toastApiError(error, 'Could not send the test event.')
    }
  }

  return (
    <div>
      <div className="detail-header">
        <div>
          <h2>Webhooks</h2>
          <p className="text-gray">Signed HTTPS notifications and delivery history</p>
        </div>
        <button className="btn-primary" onClick={() => setEditing('new')}>Add webhook</button>
      </div>
      {query.isPending ? (
        <p role="status">Loading webhooks</p>
      ) : query.isError ? (
        <div role="alert">
          {describeApiError(query.error, 'Could not load webhooks.')}
          <button className="btn-secondary" onClick={() => void query.refetch()}>Retry</button>
        </div>
      ) : (
        <div className="table-responsive">
          <table className="admin-rules-table">
            <colgroup>
              <col className="admin-col-30" />
              <col className="admin-col-24" />
              <col className="admin-col-10" />
              <col className="admin-col-14" />
              <col className="admin-col-10" />
              <col className="admin-col-12" />
            </colgroup>
            <thead>
              <tr>
                <th>URL</th>
                <th>Events</th>
                <th>Active</th>
                <th>Created</th>
                <th>Dropped events</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {query.data.map(webhook => (
                <tr key={webhook.id}>
                  <td className="mono" title={webhook.url}>{webhook.url}</td>
                  <td title={webhook.events.join(', ')}>{webhook.events.join(', ')}</td>
                  <td>
                    <span className={`badge ${webhook.active ? 'success' : 'neutral'}`}>
                      {webhook.active ? 'Active' : 'Inactive'}
                    </span>
                  </td>
                  <td>{new Date(webhook.createdAt).toLocaleString()}</td>
                  <td className="numeric">{webhook.droppedEvents}</td>
                  <td>
                    <div className="compact-table-actions" role="group" aria-label={`Actions for ${webhook.url}`}>
                      <button
                        type="button"
                        className="btn-secondary table-icon-action"
                        title="Edit"
                        aria-label="Edit"
                        onClick={() => setEditing(webhook)}
                      >
                        <Pencil size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="btn-secondary table-icon-action"
                        title={webhook.active ? 'Deactivate' : 'Activate'}
                        aria-label={webhook.active ? 'Deactivate' : 'Activate'}
                        disabled={update.isPending}
                        onClick={() => void toggle(webhook)}
                      >
                        <Power size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="btn-secondary table-icon-action"
                        title="Send test event"
                        aria-label="Send test event"
                        disabled={test.isPending}
                        onClick={() => void sendTest(webhook.id)}
                      >
                        <Play size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="btn-secondary table-icon-action"
                        title="Delivery history"
                        aria-label="Delivery history"
                        onClick={() => setHistory(webhook)}
                      >
                        <History size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="btn-secondary table-icon-action danger-text"
                        title="Delete"
                        aria-label="Delete"
                        disabled={remove.isPending}
                        onClick={() => void deleteSubscription(webhook)}
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {query.data.length === 0 && (
                <tr>
                  <td colSpan={6} className="empty-state">No webhooks yet. Add an HTTPS endpoint to receive events.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
      {editing && (
        <WebhookFormDialog
          webhook={editing === 'new' ? undefined : editing}
          onCancel={() => setEditing(null)}
          onSubmit={async body => {
            if (editing === 'new') {
              const created = await createWebhook(body)
              setSecret(created.secret)
              void client.invalidateQueries({ queryKey: webhookKeys.all })
            } else {
              await update.mutateAsync({ id: editing.id, body })
            }
            setEditing(null)
          }}
        />
      )}
      {secret !== null && <WebhookSecretDialog secret={secret} onSaved={() => setSecret(null)} />}
      {history && <DeliveryDialog key={history.id} webhook={history} onClose={() => setHistory(null)} />}
    </div>
  )
}
