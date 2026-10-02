import { expect, test, type Page } from '@playwright/test'
import { collectErrors } from './support/ui'

// The compose image prerenders the public pages from the bundled mock catalog (BUILD_SCRIPT=build:mock); the
// production image builds them from the real public API (docs/ops/E2E.md). The checks below hold for both.

interface JsonLdNode {
  '@type'?: string | string[]
  '@graph'?: JsonLdNode[]
  [key: string]: unknown
}

/** Every JSON-LD block of the page, parsed (a syntax error fails the test), flattened to its @type values. */
async function jsonLdTypes(page: Page): Promise<string[]> {
  const blocks = await page.locator('script[type="application/ld+json"]').allTextContents()
  expect(blocks.length).toBeGreaterThan(0)
  const types: string[] = []
  const visit = (node: unknown) => {
    if (Array.isArray(node)) return node.forEach(visit)
    if (!node || typeof node !== 'object') return
    const item = node as JsonLdNode
    const type = item['@type']
    if (typeof type === 'string') types.push(type)
    else if (Array.isArray(type)) types.push(...type)
    Object.values(item).forEach(visit)
  }
  for (const block of blocks) {
    const parsed: unknown = JSON.parse(block)
    expect((parsed as { '@context'?: string })['@context'] ?? 'https://schema.org').toBe('https://schema.org')
    visit(parsed)
  }
  return types
}

const pages = [
  { path: '/', lang: 'tr', title: 'EduCore — Ders ve Öğrenci Yönetim Platformu', types: ['Organization', 'WebSite'] },
  { path: '/en/', lang: 'en', title: 'EduCore — Course and Student Management Platform', types: ['Organization', 'WebSite'] },
  { path: '/courses', lang: 'tr', title: 'Ders kataloğu · EduCore', types: ['ItemList', 'Course', 'BreadcrumbList'] },
]

for (const expected of pages) {
  test(`public page ${expected.path} renders with its title and JSON-LD and no script errors`, async ({ page }) => {
    const errors = collectErrors(page)
    const response = await page.goto(expected.path, { waitUntil: 'load' })
    expect(response?.status()).toBe(200)
    await expect(page).toHaveTitle(expected.title)
    await expect(page.locator('html')).toHaveAttribute('lang', expected.lang)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.locator('link[rel="canonical"]')).toHaveCount(1)
    const types = await jsonLdTypes(page)
    for (const type of expected.types) expect(types, `JSON-LD @type ${type}`).toContain(type)
    if (expected.path === '/courses') {
      const listed = page.locator('a[href^="/courses/"]')
      expect(await listed.count()).toBeGreaterThan(0)
    }
    await page.waitForLoadState('networkidle')
    expect(errors).toEqual([])
  })
}
