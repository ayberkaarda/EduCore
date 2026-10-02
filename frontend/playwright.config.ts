import { defineConfig, devices } from '@playwright/test'

// End-to-end tests against a running compose stack (docs/ops/E2E.md). Nothing is started here: bring the stack up
// first (`docker compose up -d --build --wait`), then `npm run test:e2e`. The browser talks to the edge only
// (nginx on :3000 serves the public site and the SPA and proxies /api/ to the backend).
//
//   E2E_BASE_URL     edge origin (default http://localhost:3000; BASE_URL is accepted as well)
//   E2E_CHANNEL      browser channel: `chrome` (default locally, uses the installed Google Chrome), `msedge`,
//                    or `chromium` for Playwright's bundled browser (the default on CI)
const baseURL = (process.env.E2E_BASE_URL ?? process.env.BASE_URL ?? 'http://localhost:3000').replace(/\/+$/, '')
const requestedChannel = process.env.E2E_CHANNEL ?? (process.env.CI ? 'chromium' : 'chrome')
const channel = requestedChannel === 'chromium' ? undefined : requestedChannel

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: '**/*.spec.ts',
  // The tests share one backend and its per-IP login limit (10 attempts per minute), so they run one at a time.
  workers: 1,
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  outputDir: 'test-results',
  use: {
    baseURL,
    channel,
    viewport: { width: 1440, height: 900 },
    colorScheme: 'light',
    locale: 'en-US',
    timezoneId: 'Europe/Istanbul',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    {
      name: 'e2e',
      testIgnore: '**/screenshots.spec.ts',
      use: { ...devices['Desktop Chrome'], channel, viewport: { width: 1440, height: 900 } },
    },
    {
      // Refreshes the README screenshots (screenshots/*.png); run on demand: npx playwright test --project=screenshots
      name: 'screenshots',
      testMatch: '**/screenshots.spec.ts',
      use: { ...devices['Desktop Chrome'], channel, viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 },
    },
  ],
})
