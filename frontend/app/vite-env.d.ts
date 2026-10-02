/// <reference types="vite/client" />

// Only public values may use the VITE_ prefix: everything below is compiled into the browser bundle.
interface ImportMetaEnv {
  /** API base URL ending in /api (default "/api", same origin). See frontend/.env.example. */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
