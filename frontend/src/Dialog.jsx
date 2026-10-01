import { useEffect, useId, useRef } from 'react'
// Escape runs the dialog's Cancel/Close button. Browsers may close a modal dialog without a cancelable
// cancel event (e.g. a repeated Escape), so onClose does the same; a dialog without such a button reopens.
const dismiss = dialog => {
  const button = [...dialog.querySelectorAll('button')].find(b => /cancel|close/i.test(b.textContent))
  if (button && !button.disabled) button.click()
  else if (!dialog.open && dialog.isConnected) dialog.showModal()
}
export default function Dialog({ children, className = '' }) {
  const ref = useRef(null)
  const titleId = useId()
  useEffect(() => {
    const dialog = ref.current
    const heading = dialog.querySelector('h3')
    if (heading) heading.id = titleId
    dialog.showModal()
    return () => dialog.close()
  }, [titleId])
  return <dialog ref={ref} className={className} aria-labelledby={titleId} onCancel={e => { e.preventDefault(); dismiss(ref.current) }} onClose={() => { if (ref.current && !ref.current.open) dismiss(ref.current) }}>{children}</dialog>
}
