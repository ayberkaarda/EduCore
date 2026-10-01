import { Toaster as HotToaster, ToastBar, useToasterStore } from 'react-hot-toast'
import { CircleCheck, CircleX, Info, LoaderCircle } from 'lucide-react'
export default function Toaster() {
  const { toasts } = useToasterStore()
  const visibleIds = toasts.filter(t => t.visible).slice(0, 3).map(t => t.id)
  return <HotToaster position="bottom-right" toastOptions={{ duration: 4000, error: { duration: 8000 }, className: 'brand-toast' }}>{t => visibleIds.includes(t.id) ? <ToastBar toast={t}>{({ message }) => <><span className={'toast-icon '+t.type}>{t.type === 'success' ? <CircleCheck size={16}/> : t.type === 'error' ? <CircleX size={16}/> : t.type === 'loading' ? <LoaderCircle size={16}/> : <Info size={16}/>}</span>{message}</>}</ToastBar> : null}</HotToaster>
}
