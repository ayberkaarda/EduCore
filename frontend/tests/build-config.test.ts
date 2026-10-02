import { describe, expect, it } from 'vitest'
import { BuildConfigError, DEFAULT_AI_CRAWLERS, parseOrigin, readBuildConfig } from '../app/public-site/build-config.mjs'

const base = { PUBLIC_API_URL: 'http://localhost:8080', PUBLIC_SITE_URL: 'https://educore.example.org' }

describe('build configuration (PUBLIC_API_URL, PUBLIC_SITE_URL)', () => {
  it('fails a production build loudly when PUBLIC_API_URL is missing', () => {
    expect(() => readBuildConfig({ PUBLIC_SITE_URL: base.PUBLIC_SITE_URL }, 'production')).toThrow(BuildConfigError)
    expect(() => readBuildConfig({ PUBLIC_SITE_URL: base.PUBLIC_SITE_URL }, 'production')).toThrow(/PUBLIC_API_URL is not set/)
  })

  it('fails a production build loudly when PUBLIC_SITE_URL is missing', () => {
    expect(() => readBuildConfig({ PUBLIC_API_URL: base.PUBLIC_API_URL }, 'production')).toThrow(/PUBLIC_SITE_URL is not set.*EDUCORE_SEO_BASE_URL/)
  })

  it('defaults both origins in development only', () => {
    const config = readBuildConfig({}, 'development')
    expect(config.apiUrl).toBe('http://localhost:8080')
    expect(config.siteUrl).toBe('http://localhost:3000')
  })

  it('accepts origins the way the backend validates educore.seo.base-url', () => {
    expect(parseOrigin('X', 'https://educore.example.org/')).toBe('https://educore.example.org')
    expect(parseOrigin('X', 'http://localhost:4173')).toBe('http://localhost:4173')
    for (const invalid of ['educore.example.org', 'ftp://educore.example.org', 'https://user:pw@educore.example.org', 'https://educore.example.org/site', 'https://educore.example.org/?a=1', 'https://educore.example.org/#top', '']) {
      expect(() => parseOrigin('X', invalid), invalid).toThrow(BuildConfigError)
    }
  })

  it('mirrors educore.seo.ai-crawlers: default list, allow by default, comma-separated override', () => {
    const config = readBuildConfig(base, 'production')
    expect(config.aiCrawlers.allowPublic).toBe(true)
    expect(config.aiCrawlers.userAgents).toEqual(['GPTBot', 'ClaudeBot', 'Claude-SearchBot', 'PerplexityBot', 'Google-Extended', 'CCBot'])
    expect(DEFAULT_AI_CRAWLERS).toHaveLength(6)
    const custom = readBuildConfig({ ...base, EDUCORE_SEO_AI_CRAWLERS_ALLOW_PUBLIC: 'false', EDUCORE_SEO_AI_CRAWLERS_USER_AGENTS: 'GPTBot, CCBot,GPTBot' }, 'production')
    expect(custom.aiCrawlers).toEqual({ allowPublic: false, userAgents: ['GPTBot', 'CCBot'] })
    expect(() => readBuildConfig({ ...base, EDUCORE_SEO_AI_CRAWLERS_ALLOW_PUBLIC: 'yes' }, 'production')).toThrow(/true.*false/)
    expect(() => readBuildConfig({ ...base, EDUCORE_SEO_AI_CRAWLERS_USER_AGENTS: 'Bad Bot\nDisallow: /' }, 'production')).toThrow(BuildConfigError)
  })

  it('validates PUBLIC_COURSE_MODE', () => {
    expect(readBuildConfig(base, 'production').courseMode).toBe('Onsite')
    expect(readBuildConfig({ ...base, PUBLIC_COURSE_MODE: 'Blended' }, 'production').courseMode).toBe('Blended')
    expect(() => readBuildConfig({ ...base, PUBLIC_COURSE_MODE: 'Hybrid' }, 'production')).toThrow(/PUBLIC_COURSE_MODE/)
  })
})
