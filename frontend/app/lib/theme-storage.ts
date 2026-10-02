// The colour theme is the only value EduCore keeps in Web Storage. Tokens, role, name and every other
// session value live in memory (app/lib/api.ts, AuthProvider). ESLint bans localStorage everywhere else.

export type ThemeChoice = 'system' | 'light' | 'dark'

const STORAGE_KEY = 'educore-theme'

export function readStoredTheme(): ThemeChoice {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY)
    return stored === 'light' || stored === 'dark' ? stored : 'system'
  } catch {
    return 'system'
  }
}

export function storeTheme(theme: ThemeChoice): void {
  try {
    if (theme === 'system') window.localStorage.removeItem(STORAGE_KEY)
    else window.localStorage.setItem(STORAGE_KEY, theme)
  } catch {
    // Storage unavailable (private mode, blocked site data): the theme still applies to this page.
    return
  }
}

/** Applies a theme choice to <html data-theme>; "system" follows prefers-color-scheme. */
export function applyTheme(theme: ThemeChoice): void {
  if (theme === 'system') delete document.documentElement.dataset.theme
  else document.documentElement.dataset.theme = theme
}
