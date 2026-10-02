import { fitDescription, sentence } from '../seo/text'
import { copyFor } from './copy'
import type { PublicCourse } from './public-api.mjs'
import type { Locale } from './site-map.mjs'

/** The course description, or a neutral fallback when none is published (schema.org Course needs one). */
export function courseSummary(locale: Locale, course: PublicCourse): string {
  return course.description || copyFor(locale).course.descriptionFallback
}

/**
 * Meta description of a course page, fitted to 140-160 characters: the name with the localized term and
 * instructor labels first (so the Turkish and English pages of one course never share a description), then the
 * course description, then a fixed sentence about the page.
 */
export function courseMetaDescription(locale: Locale, course: PublicCourse): string {
  const t = copyFor(locale).course
  const details = [
    course.term ? `${t.term.toLocaleLowerCase(locale)} ${course.term}` : '',
    course.instructor ? `${t.instructor.toLocaleLowerCase(locale)} ${course.instructor}` : '',
  ].filter(Boolean).join(', ')
  const parts = [
    `${course.name}${details ? ` (${details})` : ''}:`,
    sentence(courseSummary(locale, course)),
    t.metaSuffix,
  ]
  return fitDescription(parts)
}
