import { randomInt } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { ADMIN, uniqueSuffix } from './support/api'
import { signIn } from './support/ui'

test('ADMIN adds a CIDR deny rule, is stopped from denying their own address, and deletes the rule', async ({ page }) => {
  // TEST-NET-2 (RFC 5737) never contains the test client; a random /30 keeps runs apart.
  const value = `198.51.100.${randomInt(0, 64) * 4}/30`
  const reason = `e2e ${uniqueSuffix()}`

  await signIn(page, ADMIN.username, ADMIN.password)
  await page.getByRole('navigation', { name: 'Main navigation' }).getByRole('link', { name: 'IP rules' }).click()
  await expect(page.getByRole('heading', { name: 'IP rules', level: 2 })).toBeVisible()

  await page.getByRole('button', { name: 'Add deny rule' }).click()
  let dialog = page.getByRole('dialog', { name: 'Add deny rule' })
  await dialog.getByLabel('Kind').selectOption('CIDR')
  await dialog.getByLabel('Value (required)').fill(value)
  await dialog.getByLabel('Reason').fill(reason)
  const [created] = await Promise.all([
    page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/admin/ip-rules'),
    dialog.getByRole('button', { name: 'Save rule' }).click(),
  ])
  expect(created.status(), await created.text()).toBe(201)
  await expect(dialog).toBeHidden()
  const row = page.getByRole('row').filter({ hasText: reason })
  await expect(row).toHaveCount(1)
  await expect(row.getByRole('cell').nth(0)).toHaveText('CIDR')
  await expect(row.getByRole('cell').nth(1)).toHaveText(value)
  await expect(row.getByRole('cell').nth(2)).toHaveText('MANUAL')

  // A rule that covers the ADMIN's own address is refused (409 ip-rule/self-deny) and nothing is stored.
  await page.getByRole('button', { name: 'Add deny rule' }).click()
  dialog = page.getByRole('dialog', { name: 'Add deny rule' })
  await dialog.getByLabel('Kind').selectOption('CIDR')
  await dialog.getByLabel('Value (required)').fill('0.0.0.0/0')
  const [refused] = await Promise.all([
    page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/admin/ip-rules'),
    dialog.getByRole('button', { name: 'Save rule' }).click(),
  ])
  expect(refused.status()).toBe(409)
  expect((await refused.json()).code).toBe('ip-rule/self-deny')
  await expect(dialog.getByText('This rule would block your current IP address. Choose a different range.')).toBeVisible()
  await dialog.getByRole('button', { name: 'Cancel' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('cell', { name: '0.0.0.0/0', exact: true })).toHaveCount(0)

  await row.getByRole('button', { name: `Delete ${value}` }).click()
  await page.getByRole('dialog', { name: `Delete deny rule ${value}?` }).getByRole('button', { name: 'Delete' }).click()
  await expect(row).toHaveCount(0)
  await page.reload()
  await expect(page.getByRole('columnheader', { name: 'Value' })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: reason })).toHaveCount(0)
})
