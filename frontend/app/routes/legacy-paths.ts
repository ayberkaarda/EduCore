// Pre-P8 paths whose /app/* equivalent is not simply "/app" + path.
const RENAMED: Record<string, string> = {
  '/login': '/app/login',
  '/unauthorized': '/app/login',
  '/ips': '/app/ip-allocations',
}

export function legacyTarget(pathname: string): string {
  const path = pathname.replace(/\/+$/, '') || '/'
  return RENAMED[path] ?? `/app${path}`
}
