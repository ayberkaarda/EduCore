import { expect, test } from '@playwright/test'
import { ADMIN, BASE_URL } from './support/api'
import { signIn, signOut } from './support/ui'

test('the session survives a reload through the refresh cookie and sign-out ends it', async ({ page, context }) => {
  await signIn(page, ADMIN.username, ADMIN.password)
  await expect(page.getByRole('heading', { name: 'Dashboard', level: 2 })).toBeVisible()

  // The access token lives in memory only; the HttpOnly refresh cookie restores the session after a reload.
  const refreshCookie = async () => (await context.cookies()).find(cookie => cookie.name === 'educore_rt' && cookie.value !== '')
  const first = await refreshCookie()
  expect(first, 'refresh cookie').toBeDefined()
  expect(first?.httpOnly).toBe(true)
  await page.goto('/app/students')
  await expect(page.getByRole('heading', { name: 'Students', level: 2 })).toBeVisible()
  await page.reload()
  await expect(page).toHaveURL(/\/app\/students$/)
  await expect(page.getByRole('heading', { name: 'Students', level: 2 })).toBeVisible()

  // Each refresh rotates the cookie; keep the current one to replay it after sign-out.
  const refresh = await refreshCookie()
  expect(refresh?.value).not.toBe(first?.value)

  await signOut(page)
  expect(await refreshCookie()).toBeUndefined()

  await page.reload()
  await expect(page).toHaveURL(/\/app\/login$/)
  await expect(page.getByRole('heading', { name: 'Sign in to EduCore' })).toBeVisible()
  await page.goto('/app/students')
  await expect(page).toHaveURL(/\/app\/login$/)
  await expect(page.getByRole('heading', { name: 'Sign in to EduCore' })).toBeVisible()

  // The server ended the session as well: a refresh with the old cookie is refused.
  const replay = await page.request.post('/api/v1/auth/refresh', {
    headers: { Origin: BASE_URL, Cookie: `educore_rt=${refresh?.value ?? ''}` },
  })
  expect(replay.status()).toBe(401)
})
