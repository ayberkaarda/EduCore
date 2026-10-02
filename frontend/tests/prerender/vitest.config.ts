import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

// Checks of the built output (dist/client), run after a build: `npm run test:prerender` (part of
// `npm run build:mock`). Kept apart from the unit tests because it needs the prerendered pages.
export default defineConfig({
  test: {
    root: fileURLToPath(new URL('../..', import.meta.url)),
    environment: 'jsdom',
    include: ['tests/prerender/**/*.check.ts'],
  },
})
