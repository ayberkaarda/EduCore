import { readFileSync } from 'node:fs'
import { basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { ADMIN, adminApi, bearer } from './support/api'
import { signIn } from './support/ui'

// README screenshots (screenshots/*.png, 1440x900, light theme) from the running compose stack with its dev demo
// data. Not part of `npm run test:e2e`; run on demand against a fresh stack:
//   npx playwright test --project=screenshots
// The demo data below is added through the admin API and only when missing, so repeated runs do not pile it up.

const SCREENSHOTS = fileURLToPath(new URL('../../../screenshots/', import.meta.url))
const SAMPLES = fileURLToPath(new URL('../../../csv_uploads/sample/', import.meta.url))

async function ensureImported(file: string): Promise<void> {
  const { api, token } = await adminApi()
  const logs = await api.get(`/api/v1/admin/job-logs?file=${encodeURIComponent(basename(file, '.csv'))}`, { headers: bearer(token) })
  expect(logs.status()).toBe(200)
  if ((await logs.json()).totalElements > 0) return
  const response = await api.post('/api/v1/admin/imports', {
    headers: bearer(token),
    multipart: { file: { name: basename(file), mimeType: 'text/csv', buffer: readFileSync(`${SAMPLES}${file}`) } },
  })
  expect([202, 409], await response.text()).toContain(response.status())
  await expect.poll(async () => {
    const page = await api.get(`/api/v1/admin/job-logs?file=${encodeURIComponent(basename(file, '.csv'))}`, { headers: bearer(token) })
    const body = await page.json() as { content: { status: string | null }[] }
    return body.content.some(log => log.status !== null)
  }, { timeout: 120_000 }).toBe(true)
}

async function ensureDenyRule(kind: string, value: string, reason: string): Promise<void> {
  const { api, token } = await adminApi()
  const rules = await (await api.get('/api/v1/admin/ip-rules?size=100', { headers: bearer(token) })).json() as { content: { value: string }[] }
  if (rules.content.some(rule => rule.value === value)) return
  const response = await api.post('/api/v1/admin/ip-rules', { headers: bearer(token), data: { kind, value, reason } })
  expect(response.status(), await response.text()).toBe(201)
}

async function ensureWebhook(url: string, events: string[], active: boolean): Promise<void> {
  const { api, token } = await adminApi()
  const hooks = await (await api.get('/api/v1/admin/webhooks', { headers: bearer(token) })).json() as { url: string }[]
  if (hooks.some(hook => hook.url === url)) return
  // The signing secret is only in this API response; it is never rendered.
  const response = await api.post('/api/v1/admin/webhooks', { headers: bearer(token), data: { url, events, active } })
  expect(response.status(), await response.text()).toBe(201)
}

/** Waits for fonts and for every notification to be gone, then captures the 1440x900 viewport. */
async function capture(page: Page, name: string): Promise<void> {
  for (const dismiss of await page.getByRole('button', { name: 'Dismiss notification' }).all()) await dismiss.click()
  await expect(page.locator('.brand-toast')).toHaveCount(0)
  await expect(page.locator('.spin')).toHaveCount(0)
  await page.evaluate(async () => { await document.fonts.ready })
  // Route changes focus the page heading for screen readers; drop the focus ring from the picture.
  await page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur() })
  await page.mouse.move(0, 0)
  await page.screenshot({ path: `${SCREENSHOTS}${name}.png`, fullPage: false })
}

test('README screenshots', async ({ page }) => {
  await ensureImported('students.sample.csv')
  await ensureImported('courses.sample.csv')
  await ensureDenyRule('CIDR', '203.0.113.0/24', 'Documentation range (RFC 5737)')
  await ensureDenyRule('STATIC', '192.0.2.44', 'Repeated scanner traffic')
  await ensureDenyRule('RANGE', '198.51.100.10-198.51.100.20', 'Lab network maintenance window')
  await ensureWebhook('https://hooks.example.com/educore/imports', ['import.completed', 'import.failed'], true)
  await ensureWebhook('https://hooks.example.com/educore/catalog', ['course.updated'], false)

  await page.goto('/app/login')
  await expect(page.getByRole('heading', { name: 'Sign in to EduCore' })).toBeVisible()
  await capture(page, 'login')

  await signIn(page, ADMIN.username, ADMIN.password)
  await expect(page.getByRole('heading', { name: 'Dashboard', level: 2 })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Recently added students' })).toBeVisible()
  await capture(page, 'dashboard')

  const nav = page.getByRole('navigation', { name: 'Main navigation' })
  await nav.getByRole('link', { name: 'Students' }).click()
  await expect(page.getByRole('row')).not.toHaveCount(0)
  await capture(page, 'students')

  await nav.getByRole('link', { name: 'Courses' }).click()
  await expect(page.getByRole('heading', { name: 'Courses', level: 2 })).toBeVisible()
  await capture(page, 'courses')

  await nav.getByRole('link', { name: 'Job logs' }).click()
  await expect(page.getByRole('row').filter({ hasText: 'students.sample' }).first()).toBeVisible()
  await capture(page, 'job-logs')

  await nav.getByRole('link', { name: 'IP rules' }).click()
  await expect(page.getByRole('cell', { name: '203.0.113.0/24', exact: true })).toBeVisible()
  await capture(page, 'ip-rules')

  await nav.getByRole('link', { name: 'Webhooks' }).click()
  await expect(page.getByRole('cell', { name: 'https://hooks.example.com/educore/imports', exact: true })).toBeVisible()
  await capture(page, 'webhooks')

  await page.goto('/')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await capture(page, 'public-home')

  await page.goto('/courses')
  await expect(page.locator('a[href^="/courses/"]').first()).toBeVisible()
  await capture(page, 'public-courses')
})
