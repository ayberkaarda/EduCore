import { useEffect, useState } from 'react'
import { useLocation } from 'react-router'

export default function RouteFocus() {
  const location = useLocation()
  const [title, setTitle] = useState('')
  useEffect(() => {
    let focused = false
    const focus = () => {
      if (focused) return
      const heading = document.querySelector<HTMLElement>('main h1, main h2, .page-content h1, .page-content h2')
      if (!heading) return
      focused = true
      heading.tabIndex = -1
      heading.focus()
      setTitle(heading.textContent ?? document.title)
    }
    const timer = setTimeout(focus, 0)
    const observer = new MutationObserver(focus)
    observer.observe(document.body, { childList: true, subtree: true })
    return () => {
      clearTimeout(timer)
      observer.disconnect()
    }
  }, [location.key])
  return <span className="temporary-password-announcement" aria-live="polite" aria-atomic="true">{title}</span>
}
