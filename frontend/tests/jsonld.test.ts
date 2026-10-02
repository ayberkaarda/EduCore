import { describe, expect, it } from 'vitest'
import { COPY } from '../app/public-site/copy'
import { courseSummary } from '../app/public-site/course-text'
import type { SiteFacts } from '../app/public-site/public-api.mjs'
import { breadcrumbNode, courseNode, faqNode, graph, itemListNode, organizationNode, serializeJsonLd, websiteNode } from '../app/seo/jsonld'
import { validateJsonLdBlocks } from '../app/seo/jsonld-rules.mjs'
import { MOCK_COURSES } from '../scripts/mock-public-api.mjs'

const facts: SiteFacts = {
  name: 'EduCore',
  baseUrl: 'https://educore.example.org',
  description: 'EduCore is a course and student management platform.',
  languages: ['tr', 'en'],
  dateModified: '2026-09-30T16:05:00.000Z',
}
const context = { siteUrl: facts.baseUrl, locale: 'en' as const, facts }
const hostile = MOCK_COURSES.find(course => course.slug === 'web-guvenligi')!

/** Puts a serialized graph into an HTML document and reads the block back, as a crawler would. */
function roundTrip(json: string): string[] {
  const html = `<!doctype html><html><head><script type="application/ld+json">${json}</script></head><body><p>after</p></body></html>`
  const document = new DOMParser().parseFromString(html, 'text/html')
  expect(document.querySelector('body p')?.textContent).toBe('after')
  return [...document.querySelectorAll('script[type="application/ld+json"]')].map(script => script.textContent ?? '')
}

describe('JSON-LD builders (app/seo/jsonld.tsx)', () => {
  it('builds Organization and WebSite for the landing page', () => {
    const json = serializeJsonLd(graph([organizationNode(context, ['https://github.com/ayberkaarda/EduCore']), websiteNode(context)]))
    const { problems, types } = validateJsonLdBlocks(roundTrip(json), 'home')
    expect(problems).toEqual([])
    expect(types).toEqual(['Organization', 'WebSite'])
  })

  it('builds an ItemList of Course items for the catalog', () => {
    const json = serializeJsonLd(graph([
      itemListNode(context, MOCK_COURSES, course => courseSummary('en', course)),
      breadcrumbNode(context, [{ name: 'Home', path: '/' }, { name: 'Course catalog', path: '/courses' }]),
    ]))
    const { problems, data } = validateJsonLdBlocks(roundTrip(json), 'courses')
    expect(problems).toEqual([])
    const list = data?.['@graph'][0] as { itemListElement: { item: { url: string; description: string } }[] }
    expect(list.itemListElement).toHaveLength(MOCK_COURSES.length)
    expect(list.itemListElement[0].item.url).toMatch(/^https:\/\/educore\.example\.org\/en\/courses\//)
    expect(list.itemListElement.every(element => element.item.description.length > 0)).toBe(true)
  })

  it('builds Course with CourseInstance (courseMode, instructor Person) and BreadcrumbList', () => {
    const course = MOCK_COURSES[0]
    const json = serializeJsonLd(graph([
      courseNode(context, course, courseSummary('en', course), 'Onsite'),
      breadcrumbNode(context, [{ name: 'Home', path: '/' }, { name: 'Course catalog', path: '/courses' }, { name: course.name, path: `/courses/${course.slug}` }]),
    ]))
    const { problems, data } = validateJsonLdBlocks(roundTrip(json), 'course')
    expect(problems).toEqual([])
    expect(data?.['@graph'][0]).toMatchObject({
      '@type': 'Course',
      name: course.name,
      provider: { '@id': 'https://educore.example.org/#organization' },
      hasCourseInstance: { '@type': 'CourseInstance', courseMode: 'Onsite', instructor: { '@type': 'Person', name: course.instructor } },
    })
  })

  it('builds FAQPage from the same entries the page shows', () => {
    const json = serializeJsonLd(graph([faqNode(context, COPY.en.faq.entries), breadcrumbNode(context, [{ name: 'Home', path: '/' }, { name: 'FAQ', path: '/faq' }])]))
    const { problems, data } = validateJsonLdBlocks(roundTrip(json), 'faq')
    expect(problems).toEqual([])
    expect((data?.['@graph'][0] as { mainEntity: unknown[] }).mainEntity).toHaveLength(COPY.en.faq.entries.length)
  })

  it('escapes "</script>" so a course description cannot end the element or inject markup', () => {
    const json = serializeJsonLd(graph([courseNode(context, hostile, hostile.description, 'Onsite')]))
    expect(json).not.toContain('</script>')
    expect(json).not.toContain('<')
    expect(json).toContain('\\u003c/script\\u003e')
    const blocks = roundTrip(json)
    expect(blocks).toHaveLength(1)
    expect((JSON.parse(blocks[0])['@graph'][0] as { description: string }).description).toBe(hostile.description)
  })

  it('rejects a block with raw markup, two blocks, invalid JSON or a non-public property', () => {
    expect(validateJsonLdBlocks(['{"@context":"https://schema.org","@graph":[]}', '{}'], 'home').problems[0]).toMatch(/exactly one/)
    expect(validateJsonLdBlocks(['{not json'], 'home').problems[0]).toMatch(/not valid JSON/)
    expect(validateJsonLdBlocks([JSON.stringify(graph([websiteNode(context)])).replace('EduCore', '</script>')], 'home').problems.join()).toMatch(/raw "<"/)
    const leaking = { '@context': 'https://schema.org', '@graph': [{ ...organizationNode(context, []), studentNumber: '2601005' }] }
    expect(validateJsonLdBlocks([JSON.stringify(leaking)], 'about').problems.join()).toMatch(/studentNumber is not an allowed/)
  })
})
