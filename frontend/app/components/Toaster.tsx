import { CircleCheck, CircleX, Info, X } from 'lucide-react'
import { dismissToast, useToasts } from '../lib/toast'

/** The single toast region of the app (mounted once in root.tsx). */
export default function Toaster() {
  const toasts = useToasts()
  return (
    <ol className="toast-region" aria-label="Notifications">
      {toasts.map(item => (
        <li
          key={item.id}
          className="brand-toast"
          role={item.kind === 'error' ? 'alert' : 'status'}
          aria-live={item.kind === 'error' ? 'assertive' : 'polite'}
        >
          <span className={`toast-icon ${item.kind}`}>
            {item.kind === 'success' ? <CircleCheck size={16} /> : item.kind === 'error' ? <CircleX size={16} /> : <Info size={16} />}
          </span>
          <span className="toast-message">{item.message}</span>
          {item.action && <button type="button" className="btn-secondary" onClick={item.action.onClick}>{item.action.label}</button>}
          <button type="button" className="toast-dismiss" aria-label="Dismiss notification" onClick={() => dismissToast(item.id)}>
            <X size={14} />
          </button>
        </li>
      ))}
    </ol>
  )
}
