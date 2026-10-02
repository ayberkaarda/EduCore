import type {
  BreadcrumbList,
  Course,
  CourseInstance,
  FAQPage,
  Graph,
  ItemList,
  Organization,
  Question,
  Thing,
  WebSite,
} from 'schema-dts'
import type { MetaDescriptor } from 'react-router'
import type { BuildConfig } from '../public-site/build-config.mjs'
import type { PublicCourse, SiteFacts } from '../public-site/public-api.mjs'
import { absoluteUrl, coursePath, localizedPath, type Locale } from '../public-site/site-map.mjs'

// Structured data of the public pages (docs/seo/STRUCTURED_DATA.md). Each page carries exactly one
// <script type="application/ld+json"> whose body is an @graph; React Router's <Meta> serialises it with
// JSON.stringify and escapes "<", ">", "&", U+2028 and U+2029, so a course description containing "</script>"
// cannot end the element (tests/jsonld.test.ts checks the built HTML). Only published course fields and the
// site facts are used: no student, account or enrollment data exists in any input of these builders.

/** CourseInstance.courseMode for every course: the public API has no per-course mode (see STRUCTURED_DATA.md). */
type CourseMode = BuildConfig['courseMode']

export interface JsonLdContext {
  siteUrl: string
  locale: Locale
  facts: SiteFacts
}

export interface Crumb {
  name: string
  path: string
}

export function organizationId(siteUrl: string): string {
  return `${siteUrl}/#organization`
}

export function websiteId(siteUrl: string): string {
  return `${siteUrl}/#website`
}

export function organizationNode({ siteUrl, facts }: JsonLdContext, sameAs: readonly string[]): Exclude<Organization, string> {
  return {
    '@type': 'Organization',
    '@id': organizationId(siteUrl),
    name: facts.name,
    url: absoluteUrl(siteUrl, '/'),
    logo: { '@type': 'ImageObject', url: `${siteUrl}/brand/logo-512.png`, width: '512', height: '512' },
    description: facts.description,
    sameAs: [...sameAs],
  }
}

export function websiteNode({ siteUrl, facts, locale }: JsonLdContext): WebSite {
  return {
    '@type': 'WebSite',
    '@id': websiteId(siteUrl),
    url: absoluteUrl(siteUrl, localizedPath(locale, '/')),
    name: facts.name,
    description: facts.description,
    inLanguage: locale,
    publisher: { '@id': organizationId(siteUrl) },
    ...(facts.dateModified ? { dateModified: facts.dateModified } : {}),
  }
}

function courseUrl(siteUrl: string, locale: Locale, slug: string): string {
  return absoluteUrl(siteUrl, localizedPath(locale, coursePath(slug)))
}

/** A Course node; `description` must already contain a fallback when the course has none (Course requires it). */
export function courseNode(
  { siteUrl, locale }: JsonLdContext,
  course: PublicCourse,
  description: string,
  courseMode: CourseMode,
): Course {
  const url = courseUrl(siteUrl, locale, course.slug)
  const instance: CourseInstance = {
    '@type': 'CourseInstance',
    courseMode,
    ...(course.term ? { name: course.term } : {}),
    ...(course.instructor ? { instructor: { '@type': 'Person', name: course.instructor } } : {}),
  }
  return {
    '@type': 'Course',
    '@id': `${url}#course`,
    name: course.name,
    description,
    url,
    provider: { '@id': organizationId(siteUrl) },
    dateModified: course.updatedAt,
    hasCourseInstance: instance,
  }
}

export function itemListNode(context: JsonLdContext, courses: readonly PublicCourse[], describe: (course: PublicCourse) => string): ItemList {
  return {
    '@type': 'ItemList',
    '@id': `${absoluteUrl(context.siteUrl, localizedPath(context.locale, '/courses'))}#list`,
    numberOfItems: courses.length,
    itemListOrder: 'https://schema.org/ItemListOrderAscending',
    itemListElement: courses.map((course, index) => ({
      '@type': 'ListItem',
      position: index + 1,
      item: {
        '@type': 'Course',
        name: course.name,
        description: describe(course),
        url: courseUrl(context.siteUrl, context.locale, course.slug),
        provider: { '@id': organizationId(context.siteUrl) },
      },
    })),
  }
}

export function breadcrumbNode({ siteUrl, locale }: JsonLdContext, crumbs: readonly Crumb[]): BreadcrumbList {
  return {
    '@type': 'BreadcrumbList',
    itemListElement: crumbs.map((crumb, index) => ({
      '@type': 'ListItem',
      position: index + 1,
      name: crumb.name,
      item: absoluteUrl(siteUrl, localizedPath(locale, crumb.path)),
    })),
  }
}

export function faqNode(context: JsonLdContext, entries: readonly { question: string; answer: string }[]): FAQPage {
  const questions: Question[] = entries.map(entry => ({
    '@type': 'Question',
    name: entry.question,
    acceptedAnswer: { '@type': 'Answer', text: entry.answer },
  }))
  return {
    '@type': 'FAQPage',
    '@id': `${absoluteUrl(context.siteUrl, localizedPath(context.locale, '/faq'))}#faq`,
    inLanguage: context.locale,
    mainEntity: questions,
  }
}

export function graph(nodes: readonly Thing[]): Graph {
  return { '@context': 'https://schema.org', '@graph': nodes }
}

/** The single JSON-LD block of a page, as a React Router meta descriptor. */
export function jsonLdMeta(nodes: readonly Thing[]): MetaDescriptor {
  return { 'script:ld+json': graph(nodes) }
}

/**
 * Serialises a graph the way it must appear inside a <script> element: "<", ">", "&", U+2028 and U+2029 become
 * JSON unicode escapes. Mirrors React Router's escaping; used by tests and by scripts that embed JSON-LD.
 */
export function serializeJsonLd(value: Graph): string {
  return JSON.stringify(value)
    .replace(/</g, '\\u003c')
    .replace(/>/g, '\\u003e')
    .replace(/&/g, '\\u0026')
    .replace(new RegExp(String.fromCharCode(0x2028), 'g'), '\\u2028')
    .replace(new RegExp(String.fromCharCode(0x2029), 'g'), '\\u2029')
}
