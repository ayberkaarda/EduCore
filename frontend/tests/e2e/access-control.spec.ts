import { expect, test } from '@playwright/test'
import { bearer, createActiveUser } from './support/api'
import { signIn } from './support/ui'

test('a USER sees the access-denied panel on an admin page and the admin API answers 403', async ({ page }) => {
  const user = await createActiveUser('Viewer')
  const session = await signIn(page, user.username, user.password)
  expect(session.user.role).toBe('USER')
  await expect(page).toHaveURL(/\/app$/)
  await expect(page.getByRole('heading', { name: 'My profile', level: 2 })).toBeVisible()

  // The admin links are not offered to a USER.
  const nav = page.getByRole('navigation', { name: 'Main navigation' })
  await expect(nav.getByRole('link', { name: 'Users' })).toHaveCount(0)
  await expect(nav.getByRole('link', { name: 'Students' })).toHaveCount(0)

  await page.goto('/app/users')
  const denied = page.getByRole('alert').filter({ hasText: 'You do not have access to this page' })
  await expect(denied).toBeVisible()
  await expect(denied.getByRole('link', { name: 'Go to my profile' })).toBeVisible()

  // The API enforces the role independently of the UI (RBAC matrix rows 11-21: USER -> 403).
  for (const path of ['/api/v1/admin/accounts', '/api/v1/admin/accounts/students', '/api/v1/admin/ip-rules', '/api/v1/admin/job-logs']) {
    const response = await page.request.get(path, { headers: bearer(session.accessToken) })
    expect(response.status(), path).toBe(403)
    expect(response.headers()['content-type'], path).toContain('application/problem+json')
  }
  // The same token is valid for the user's own routes.
  const own = await page.request.get('/api/v1/me', { headers: bearer(session.accessToken) })
  expect(own.status()).toBe(200)

  await denied.getByRole('link', { name: 'Go to my profile' }).click()
  await expect(page).toHaveURL(/\/app\/profile$/)
  await expect(page.getByRole('heading', { name: 'My profile', level: 2 })).toBeVisible()
})
