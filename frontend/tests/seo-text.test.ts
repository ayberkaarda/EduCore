import { describe, expect, it } from 'vitest'
import { fitDescription, pageTitle, sentence, truncateAtWord } from '../app/seo/text'
import { absoluteUrl, localeFromPath, localizedPath, neutralPath, prerenderPaths } from '../app/public-site/site-map.mjs'

describe('title and description fitting', () => {
  it('keeps short page names and appends the suffix', () => {
    expect(pageTitle('Doğrusal Cebir')).toBe('Doğrusal Cebir · EduCore')
  })

  it('shortens long names at a word boundary so the title stays within 60 characters', () => {
    const title = pageTitle('Bilgisayar Mühendisliğinde Proje Yönetimi, Yazılım Kalitesi ve Mesleki Etik Semineri')
    expect(title.length).toBeLessThanOrEqual(60)
    expect(title).toBe('Bilgisayar Mühendisliğinde Proje Yönetimi… · EduCore')
  })

  it('cuts a single very long word instead of producing an empty title', () => {
    const title = pageTitle('x'.repeat(200))
    expect(title.length).toBe(60)
    expect(title.endsWith('… · EduCore')).toBe(true)
  })

  it('fits descriptions into 140-160 characters', () => {
    const short = fitDescription(['A one-line summary.', 'A second sentence that adds context for search results.', 'A third sentence that pushes the total well past the minimum length.', 'A fourth sentence that is never used.'])
    expect(short.length).toBeGreaterThanOrEqual(140)
    expect(short.length).toBeLessThanOrEqual(160)
    const long = fitDescription(['word '.repeat(80)])
    expect(long.length).toBeLessThanOrEqual(160)
    expect(long.endsWith('…')).toBe(true)
  })

  it('normalises whitespace and sentence endings', () => {
    expect(truncateAtWord('  a   b  ', 10)).toBe('a b')
    expect(sentence('Term: 2026 Güz')).toBe('Term: 2026 Güz.')
    expect(sentence('Done.')).toBe('Done.')
  })
})

describe('public URL scheme (docs/seo/I18N.md)', () => {
  it('keeps Turkish at the root and English under /en', () => {
    expect(localizedPath('tr', '/')).toBe('/')
    expect(localizedPath('en', '/')).toBe('/en')
    expect(localizedPath('en', '/courses/dogrusal-cebir')).toBe('/en/courses/dogrusal-cebir')
    expect(localeFromPath('/en')).toBe('en')
    expect(localeFromPath('/en/faq')).toBe('en')
    expect(localeFromPath('/english')).toBe('tr')
    expect(neutralPath('/en/faq')).toBe('/faq')
    expect(neutralPath('/en')).toBe('/')
    expect(absoluteUrl('https://educore.example.org', '/')).toBe('https://educore.example.org/')
  })

  it('prerenders every page in both languages plus the not-found pages', () => {
    const paths = prerenderPaths(['dogrusal-cebir'])
    expect(paths).toEqual([
      '/', '/courses', '/about', '/faq', '/privacy', '/security', '/courses/dogrusal-cebir', '/404',
      '/en', '/en/courses', '/en/about', '/en/faq', '/en/privacy', '/en/security', '/en/courses/dogrusal-cebir', '/en/404',
    ])
  })
})
