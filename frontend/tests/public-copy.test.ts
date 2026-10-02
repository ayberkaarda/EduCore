import { describe, expect, it } from 'vitest'
import { COPY } from '../app/public-site/copy'
import { courseMetaDescription } from '../app/public-site/course-text'
import { pageTitle, DESCRIPTION_MAX, DESCRIPTION_MIN, TITLE_MAX } from '../app/seo/text'
import { MOCK_COURSES } from '../scripts/mock-public-api.mjs'

const locales = ['tr', 'en'] as const
const pages = ['home', 'about', 'faq', 'privacy', 'security', 'notFound'] as const

function words(text: string) {
  return text.split(/\s+/).filter(Boolean).length
}

describe('public copy (docs/seo/META.md, GEO rules)', () => {
  it.each(locales)('%s: every hand-written description has 140-160 characters', locale => {
    for (const page of pages) {
      const { description } = COPY[locale][page]
      expect(description.length, `${locale} ${page}`).toBeGreaterThanOrEqual(DESCRIPTION_MIN)
      expect(description.length, `${locale} ${page}`).toBeLessThanOrEqual(DESCRIPTION_MAX)
    }
  })

  it.each(locales)('%s: the catalog description stays in range for 1 to 10,000 courses', locale => {
    for (const count of [1, 9, 10, 99, 100, 999, 10_000]) {
      const length = COPY[locale].courses.description(count).length
      expect(length, `${count} courses`).toBeGreaterThanOrEqual(DESCRIPTION_MIN)
      expect(length, `${count} courses`).toBeLessThanOrEqual(DESCRIPTION_MAX)
    }
  })

  it('uses the specified landing titles and the "<Page> · EduCore" pattern within 60 characters', () => {
    expect(COPY.en.home.title).toBe('EduCore — Course and Student Management Platform')
    expect(COPY.tr.home.title).toBe('EduCore — Ders ve Öğrenci Yönetim Platformu')
    for (const locale of locales) {
      expect(COPY[locale].home.title.length).toBeLessThanOrEqual(TITLE_MAX)
      for (const page of ['about', 'faq', 'privacy', 'security'] as const) {
        expect(pageTitle(COPY[locale][page].title)).toMatch(/^.+ · EduCore$/)
        expect(pageTitle(COPY[locale][page].title).length).toBeLessThanOrEqual(TITLE_MAX)
      }
    }
  })

  it.each(locales)('%s: landing, about and FAQ answer what/for whom/what it does in at most 60 words', locale => {
    for (const page of ['home', 'about', 'faq'] as const) {
      const lead = COPY[locale][page].lead
      expect(words(lead), `${locale} ${page}`).toBeLessThanOrEqual(60)
      expect(lead).toMatch(/EduCore/)
    }
    expect(COPY[locale].home.lead).toMatch(locale === 'en' ? /educational institutions/ : /eğitim kurumları/)
  })

  it.each(locales)('%s: section headings and FAQ entries are phrased as questions', locale => {
    const headings = [
      ...COPY[locale].home.sections, ...COPY[locale].about.sections, ...COPY[locale].privacy.sections,
      ...COPY[locale].security.sections,
    ].map(section => section.heading)
    headings.push(COPY[locale].home.catalogHeading, COPY[locale].course.enrolHeading, ...COPY[locale].faq.entries.map(entry => entry.question))
    for (const heading of headings) expect(heading, heading).toMatch(/\?$/)
  })

  it('names the product consistently and contains no placeholder text', () => {
    const all = JSON.stringify(COPY) + Object.values(COPY).map(copy => copy.courses.description(3) + copy.courses.lead(3)).join(' ')
    expect(all).not.toMatch(/Educore|EDUCORE|Edu Core|edu-core|project3/)
    expect(all).not.toMatch(/lorem|ipsum|TODO|FIXME|TBD|example\.com/i)
    expect(all).not.toMatch(/!/)
  })

  it('states the same facts with the same numbers in both languages', () => {
    for (const fact of ['15', '14', '30', '90', '365', '12']) {
      const inTurkish = JSON.stringify(COPY.tr).includes(fact)
      const inEnglish = JSON.stringify(COPY.en).includes(fact)
      expect(inTurkish, fact).toBe(inEnglish)
    }
    expect(COPY.tr.faq.entries).toHaveLength(COPY.en.faq.entries.length)
  })

  it.each(locales)('%s: generated course descriptions fit 140-160 characters and are unique', locale => {
    const seen = new Set<string>()
    for (const course of MOCK_COURSES) {
      const description = courseMetaDescription(locale, course)
      expect(description.length, course.slug).toBeGreaterThanOrEqual(DESCRIPTION_MIN)
      expect(description.length, course.slug).toBeLessThanOrEqual(DESCRIPTION_MAX)
      expect(seen.has(description)).toBe(false)
      seen.add(description)
    }
  })

  it('never gives the Turkish and English page of a course the same description', () => {
    for (const course of MOCK_COURSES) {
      expect(courseMetaDescription('tr', course)).not.toBe(courseMetaDescription('en', course))
    }
  })
})
