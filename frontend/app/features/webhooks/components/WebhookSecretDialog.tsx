import { useState } from 'react'
import Dialog from '../../../components/Dialog'
export default function WebhookSecretDialog({ secret, onSaved }: { secret: string; onSaved: () => void }) {
  const [message, setMessage] = useState('')
  const copy = async () => { try { await navigator.clipboard.writeText(secret); setMessage('Secret copied.') } catch { setMessage('Copy the secret manually from the field below.') } }
  return <div className="modal-overlay"><Dialog className="modal-content temporary-password-dialog"><h3>Webhook created</h3><p id="secret-warning">This signing secret is shown only once. Save it securely before continuing. A lost secret requires deleting and recreating the subscription.</p><div className="form-group"><label htmlFor="webhook-secret">Signing secret</label><input id="webhook-secret" className="temporary-password-field" value={secret} readOnly autoComplete="off" spellCheck={false} aria-describedby="secret-warning" /></div><p role="status">{message}</p><div className="modal-actions"><button className="btn-secondary" onClick={() => void copy()}>Copy secret</button><button className="btn-primary" onClick={onSaved}>I have saved it</button></div></Dialog></div>
}
