import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, test } from '@playwright/test'
import { ADMIN, uniqueLetters } from './support/api'
import { signIn } from './support/ui'

const SAMPLE = fileURLToPath(new URL('../../../csv_uploads/sample/students.sample.csv', import.meta.url))

/**
 * The students sample (csv_uploads/sample) with this run's own student numbers and last names, so the import is
 * valid on every run (the sample's numbers exist after the first import, and an identical file is a duplicate).
 */
function uniqueStudentsCsv(): { name: string; content: string; rows: number } {
  const [header, ...rows] = readFileSync(SAMPLE, 'utf8').trim().split(/\r?\n/)
  const base = 100_000_000 + (Date.now() % 800_000_000)
  const tag = uniqueLetters(6)
  const body = rows.map((row, index) => {
    const [firstName] = row.split(',')
    return `${firstName},Import ${tag},${base + index}`
  })
  return { name: `e2e-students-${tag}.csv`, content: `${header}\n${body.join('\n')}\n`, rows: body.length }
}

test('ADMIN uploads a CSV and its job log reaches a final status', async ({ page }) => {
  const csv = uniqueStudentsCsv()
  await signIn(page, ADMIN.username, ADMIN.password)
  await page.getByRole('navigation', { name: 'Main navigation' }).getByRole('link', { name: 'Import CSV' }).click()
  await expect(page.getByRole('heading', { name: 'Import CSV', level: 2 })).toBeVisible()

  await page.getByLabel('CSV file (required)').setInputFiles({ name: csv.name, mimeType: 'text/csv', buffer: Buffer.from(csv.content, 'utf8') })
  const [upload] = await Promise.all([
    page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/admin/imports'),
    page.getByRole('button', { name: 'Upload CSV' }).click(),
  ])
  expect(upload.status(), await upload.text()).toBe(202)
  const accepted = await upload.json() as { inboxFileName: string; rows: number }
  expect(accepted.rows).toBe(csv.rows)
  const status = page.getByRole('status').filter({ has: page.getByRole('heading', { name: 'Import accepted' }) })
  await expect(status).toContainText(accepted.inboxFileName)
  await expect(status).toContainText(`${csv.rows} rows`)

  await page.getByRole('link', { name: 'View job logs' }).click()
  await expect(page.getByRole('heading', { name: 'Job logs', level: 2 })).toBeVisible()
  await page.getByLabel('File').fill(accepted.inboxFileName)
  await page.getByRole('button', { name: 'Apply filters' }).click()

  // The ingestion pipeline picks the file up from the inbox asynchronously; poll the list by reloading the
  // filtered view from the server until the row reports its final status.
  const row = page.getByRole('row').filter({ hasText: accepted.inboxFileName })
  await expect(async () => {
    await page.getByRole('link', { name: 'Import CSV' }).first().click()
    await page.getByRole('link', { name: 'View job logs' }).click()
    await page.getByLabel('File').fill(accepted.inboxFileName)
    await page.getByRole('button', { name: 'Apply filters' }).click()
    await expect(row).toHaveCount(1, { timeout: 2_000 })
    await expect(row.locator('.badge').filter({ hasText: /^(Success|Partial)$/ })).toBeVisible({ timeout: 2_000 })
  }).toPass({ timeout: 120_000, intervals: [1_000, 2_000, 5_000] })
  await expect(row.getByRole('cell').nth(4)).toHaveText(String(csv.rows))

  await row.getByRole('button', { name: 'Details' }).click()
  const details = page.getByRole('dialog', { name: /Execution details/ })
  await expect(details).toBeVisible()
  await details.getByRole('button', { name: 'Close details' }).click()
  await expect(details).toBeHidden()
})
