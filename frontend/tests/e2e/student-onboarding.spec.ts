import { expect, test } from '@playwright/test'
import { ADMIN, bearer, strongPassword, uniqueLetters } from './support/api'
import { signIn, signOut } from './support/ui'

test('ADMIN creates a student who must replace the temporary password before using the app', async ({ page }) => {
  const firstName = 'Onboard'
  const lastName = `Student ${uniqueLetters()}`

  await signIn(page, ADMIN.username, ADMIN.password)
  await expect(page).toHaveURL(/\/app$/)
  await expect(page.getByRole('heading', { name: 'Dashboard', level: 2 })).toBeVisible()
  await expect(page.getByText('Total students')).toBeVisible()

  await page.getByRole('navigation', { name: 'Main navigation' }).getByRole('link', { name: 'Students' }).click()
  await expect(page.getByRole('heading', { name: 'Students', level: 2 })).toBeVisible()
  await page.getByRole('button', { name: 'Add student' }).click()
  const addDialog = page.getByRole('dialog', { name: 'Add student' })
  await addDialog.getByLabel('First name (required)').fill(firstName)
  await addDialog.getByLabel('Last name (optional)').fill(lastName)
  const [created] = await Promise.all([
    page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/api/v1/admin/accounts/students')),
    addDialog.getByRole('button', { name: 'Save' }).click(),
  ])
  expect(created.status()).toBe(201)
  const student = await created.json() as { username: string }

  // The one-time password dialog names the username and cannot be dismissed with Escape or a backdrop click.
  const passwordDialog = page.getByRole('dialog', { name: 'Student created' })
  await expect(passwordDialog).toBeVisible()
  await expect(passwordDialog.getByText(`Username: ${student.username}`)).toBeVisible()
  const temporaryPassword = await passwordDialog.getByLabel('Temporary password').inputValue()
  expect(temporaryPassword.length).toBeGreaterThanOrEqual(12)
  await page.keyboard.press('Escape')
  await expect(passwordDialog).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(passwordDialog).toBeVisible()
  const box = await passwordDialog.boundingBox()
  expect(box).not.toBeNull()
  await page.mouse.click(Math.max(5, (box?.x ?? 0) - 40), Math.max(5, (box?.y ?? 0) - 40))
  await expect(passwordDialog).toBeVisible()
  await passwordDialog.getByRole('button', { name: 'I have saved it' }).click()
  await expect(passwordDialog).toBeHidden()
  // Shown once: after closing, the password is nowhere in the page.
  await expect(page.locator('body')).not.toContainText(temporaryPassword)
  expect(await page.locator('input').evaluateAll((inputs, value) => inputs.some(input => (input as HTMLInputElement).value === value), temporaryPassword)).toBe(false)

  await signOut(page)

  // First sign-in with the temporary password: held on the change-password screen.
  const session = await signIn(page, student.username, temporaryPassword)
  expect(session.user.mustChangePassword).toBe(true)
  await expect(page).toHaveURL(/\/app\/change-password$/)
  await expect(page.getByRole('heading', { name: 'Change your temporary password' })).toBeVisible()
  for (const path of ['/app', '/app/profile', '/app/courses', '/app/students']) {
    await page.goto(path)
    await expect(page).toHaveURL(/\/app\/change-password$/)
    await expect(page.getByRole('heading', { name: 'Change your temporary password' })).toBeVisible()
  }

  // The API enforces the same rule: only the session endpoints answer, everything else is 403.
  const token = session.accessToken
  const me = await page.request.get('/api/v1/auth/me', { headers: bearer(token) })
  expect(me.status()).toBe(200)
  expect((await me.json()).mustChangePassword).toBe(true)
  for (const path of ['/api/v1/me', '/api/v1/courses', '/api/v1/me/enrollments', '/api/v1/weather', '/api/v1/admin/accounts']) {
    const response = await page.request.get(path, { headers: bearer(token) })
    expect(response.status(), path).toBe(403)
    expect((await response.json()).code, path).toBe('account/password-change-required')
  }

  // The reload above dropped the in-memory token; the refresh cookie keeps the user on the change screen.
  const newPassword = strongPassword()
  await page.getByLabel('Current password (required)').fill(temporaryPassword)
  await page.getByLabel('New password (required)', { exact: true }).fill(newPassword)
  await page.getByLabel('Repeat new password (required)', { exact: true }).fill(newPassword)
  await page.getByRole('button', { name: 'Change password' }).click()
  await expect(page).toHaveURL(/\/app$/)
  await expect(page.getByRole('heading', { name: 'My profile', level: 2 })).toBeVisible()
  await expect(page.getByRole('heading', { name: `${firstName} ${lastName}` })).toBeVisible()

  await page.getByRole('navigation', { name: 'Main navigation' }).getByRole('link', { name: 'Courses' }).click()
  await expect(page).toHaveURL(/\/app\/courses$/)
  await expect(page.getByRole('heading', { name: 'Change your temporary password' })).toBeHidden()
})
