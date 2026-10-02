/** Only paths inside the app are accepted as a post-login destination (no open redirect). */
export function safeReturnPath(from: unknown): string {
  if (typeof from !== 'string') return '/app'
  const insideApp = from === '/app' || from.startsWith('/app/') || from.startsWith('/app?')
  if (!insideApp || from.startsWith('/app/login') || from.startsWith('/app/change-password')) return '/app'
  return from
}
