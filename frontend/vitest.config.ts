import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// Tests render route components directly, so they use the plain React plugin instead of the
// React Router framework plugin (which expects to own the whole app build).
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    environmentOptions: { jsdom: { url: 'http://localhost:3000/' } },
    setupFiles: ['./tests/setup.ts'],
    include: ['tests/**/*.test.{ts,tsx}'],
    css: false,
    restoreMocks: true,
  },
})
