import { expect, type Page, type Response } from '@playwright/test'
import type { Session } from './api'

const isLogin = (response: Response) => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/auth/login'

/**
 * Signs in through the login screen and returns the session the server answered with (its access token lets a test
 * call the API as that user with page.request). On a 429 the screen disables the button for the Retry-After seconds;
 * the helper waits for the button instead of sleeping and submits again.
 */
export async function signIn(page: Page, username: string, password: string): Promise<Session> {
  if (new URL(page.url(), 'http://x').pathname !== '/app/login') await page.goto('/app/login')
  await page.getByLabel('Username (required)', { exact: true }).fill(username)
  await page.getByLabel('Password (required)', { exact: true }).fill(password)
  const submit = page.getByRole('button', { name: 'Sign in', exact: true })
  for (let attempt = 0; attempt < 4; attempt += 1) {
    await expect(submit).toBeEnabled({ timeout: 90_000 })
    const [response] = await Promise.all([page.waitForResponse(isLogin), submit.click()])
    if (response.status() === 429) continue
    expect(response.status(), `login of ${username}`).toBe(200)
    return await response.json() as Session
  }
  throw new Error(`login of ${username} stayed rate limited`)
}

/** Signs out through the sidebar and waits for the login screen. */
export async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/\/app\/login$/)
  await expect(page.getByRole('heading', { name: 'Sign in to EduCore' })).toBeVisible()
}

/** Collects uncaught page errors and console errors; assert on the list at the end of a test. */
export function collectErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(`pageerror: ${error.message}`))
  page.on('console', message => {
    if (message.type() === 'error') errors.push(`console: ${message.text()}`)
  })
  return errors
}
