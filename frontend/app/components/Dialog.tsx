import { useEffect, useId, useRef, type ReactNode } from 'react'

// Escape runs the dialog's Cancel/Close button. Browsers may close a modal dialog without a cancelable
// cancel event (e.g. a repeated Escape), so onClose does the same; a dialog without such a button reopens.
function dismiss(dialog: HTMLDialogElement) {
  const button = [...dialog.querySelectorAll('button')].find(item => /cancel|close/i.test(item.textContent ?? ''))
  if (button && !button.disabled) button.click()
  else if (!dialog.open && dialog.isConnected) dialog.showModal()
}

interface DialogProps {
  children: ReactNode
  className?: string
}

export default function Dialog({ children, className = '' }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    const heading = dialog.querySelector('h3')
    if (heading) heading.id = titleId
    if (!dialog.open) dialog.showModal()
    return () => dialog.close()
  }, [titleId])
  return (
    <dialog
      ref={ref}
      className={className}
      aria-labelledby={titleId}
      onCancel={event => {
        event.preventDefault()
        if (ref.current) dismiss(ref.current)
      }}
      onClose={() => {
        if (ref.current && !ref.current.open) dismiss(ref.current)
      }}
    >
      {children}
    </dialog>
  )
}
