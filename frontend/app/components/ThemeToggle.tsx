import { useEffect, useState } from 'react'
import { applyTheme, readStoredTheme, storeTheme, type ThemeChoice } from '../lib/theme-storage'

/** Theme picker for the sign-in page and the app shell (client-rendered only). */
export default function ThemeToggle() {
  const [theme, setTheme] = useState<ThemeChoice>(readStoredTheme)
  useEffect(() => {
    applyTheme(theme)
    storeTheme(theme)
  }, [theme])
  return (
    <label className="theme-toggle">
      <span>Theme</span>
      <select aria-label="Color theme" value={theme} onChange={event => setTheme(event.target.value as ThemeChoice)}>
        <option value="system">System</option>
        <option value="light">Light</option>
        <option value="dark">Dark</option>
      </select>
    </label>
  )
}
