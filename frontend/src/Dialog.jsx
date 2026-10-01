import { useEffect, useId, useRef } from 'react'
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
  return <dialog ref={ref} className={className} aria-labelledby={titleId} onCancel={e => { e.preventDefault(); const buttons = [...ref.current.querySelectorAll('button')]; buttons.find(b => /cancel|close/i.test(b.textContent))?.click() }}>{children}</dialog>
}
