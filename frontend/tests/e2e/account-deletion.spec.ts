import { expect, test } from '@playwright/test'
import { bearer, createActiveUser } from './support/api'
import { signIn } from './support/ui'

test('a USER schedules account deletion, is limited to restore or sign-out, and restores the account', async ({ page }) => {
  const user = await createActiveUser('Leaver')
  await signIn(page, user.username, user.password)
  await expect(page.getByRole('heading', { name: 'My profile', level: 2 })).toBeVisible()

  await page.getByRole('button', { name: 'Delete my account' }).click()
  const dialog = page.getByRole('dialog', { name: 'Delete my account' })
  await dialog.getByLabel('Current password (required)').fill(user.password)
  const [scheduled] = await Promise.all([
    page.waitForResponse(response => response.request().method() === 'DELETE' && new URL(response.url()).pathname === '/api/v1/me'),
    dialog.getByRole('button', { name: 'Schedule deletion' }).click(),
  ])
  expect(scheduled.status()).toBe(202)

  // Every session ended: the user is sent to the login screen with the scheduled date.
  const { deleteAfter } = await scheduled.json() as { deleteAfter: string }
  await expect(page).toHaveURL(/\/app\/login$/)
  await expect(page.getByRole('heading', { name: 'Sign in to EduCore' })).toBeVisible()
  const notice = page.getByRole('status').filter({ hasText: 'Deletion scheduled until' })
  await expect(notice).toHaveText(/Deletion scheduled until .+; sign in to restore\./)
  await expect(notice).toContainText(String(new Date(deleteAfter).getUTCFullYear()))

  const session = await signIn(page, user.username, user.password)
  expect(session.user.status).toBe('PENDING_DELETION')
  const screen = page.getByRole('main')
  await expect(screen.getByRole('heading', { name: 'Deletion scheduled' })).toBeVisible()
  await expect(screen.getByRole('button')).toHaveText(['Restore', 'Sign out'])
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toHaveCount(0)

  // Other pages stay out of reach, in the UI and in the API (restore-only scope).
  await page.goto('/app/courses')
  await expect(page.getByRole('heading', { name: 'Deletion scheduled' })).toBeVisible()
  await expect(page.getByRole('main').getByRole('button')).toHaveText(['Restore', 'Sign out'])
  const blocked = await page.request.get('/api/v1/courses', { headers: bearer(session.accessToken) })
  expect(blocked.status()).toBe(403)
  expect((await blocked.json()).code).toBe('account/pending-deletion')

  await page.getByLabel('Current password (required)').fill(user.password)
  await page.getByRole('button', { name: 'Restore' }).click()
  await expect(page).toHaveURL(/\/app\/courses$/)
  await expect(page.getByRole('heading', { name: 'Deletion scheduled' })).toBeHidden()
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible()
  await page.getByRole('navigation', { name: 'Main navigation' }).getByRole('link', { name: 'My profile' }).click()
  await expect(page.getByRole('heading', { name: 'My profile', level: 2 })).toBeVisible()
  await expect(page.getByRole('heading', { name: `${user.firstName} ${user.lastName}` })).toBeVisible()
})
