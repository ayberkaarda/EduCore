import { reactRouter } from '@react-router/dev/vite'
import { defineConfig, loadEnv } from 'vite'

// The dev server and `vite preview` forward /api to the backend, so the browser talks to one origin and no
// CORS is needed in development. EDUCORE_DEV_PROXY_TARGET is read here only (it has no VITE_ prefix, so it
// never reaches the browser bundle). See .env.example.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxy = {
    '/api': {
      target: env.EDUCORE_DEV_PROXY_TARGET || 'http://localhost:8080',
      changeOrigin: true,
    },
  }
  return {
    plugins: [reactRouter()],
    // Port 3000 is the origin the backend's dev profile allows for CORS and for the refresh/logout Origin check.
    server: { port: 3000, strictPort: true, proxy },
    preview: { port: 3000, strictPort: true, proxy },
  }
})
