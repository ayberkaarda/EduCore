import { useEffect, useState } from 'react'

const STORAGE_KEY = 'educore-theme'

// Keep the chosen theme across the sign-in page and the app shell (and reloads).
function readStoredTheme() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored === 'light' || stored === 'dark' ? stored : 'system'
  } catch {
    return 'system'
  }
}

export default function ThemeToggle() {
  const [theme, setTheme] = useState(readStoredTheme)
  useEffect(() => {
    if (theme === 'system') delete document.documentElement.dataset.theme
    else document.documentElement.dataset.theme = theme
    try {
      if (theme === 'system') localStorage.removeItem(STORAGE_KEY)
      else localStorage.setItem(STORAGE_KEY, theme)
    } catch { /* storage unavailable: theme still applies for this page */ }
  }, [theme])
  return <label className="theme-toggle"><span>Theme</span><select aria-label="Color theme" value={theme} onChange={e => setTheme(e.target.value)}><option value="system">System</option><option value="light">Light</option><option value="dark">Dark</option></select></label>
}
