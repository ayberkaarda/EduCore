// Validation rules for the JSON-LD of the prerendered pages, shared by scripts/check-seo.mjs (build gate) and
// tests/prerender/jsonld.check.ts. Returns a list of problems (empty when valid). Types: jsonld-rules.d.mts.

/** Every property the public JSON-LD may use. Anything else (for example a student or account field) fails. */
export const ALLOWED_KEYS = new Set([
  '@context', '@graph', '@type', '@id', 'name', 'url', 'logo', 'width', 'height', 'description', 'sameAs',
  'inLanguage', 'publisher', 'dateModified', 'numberOfItems', 'itemListOrder', 'itemListElement', 'position',
  'item', 'provider', 'hasCourseInstance', 'courseMode', 'instructor', 'mainEntity', 'acceptedAnswer', 'text',
])

/** Required @type values per page kind (docs/seo/STRUCTURED_DATA.md). */
export const REQUIRED_TYPES = Object.freeze({
  home: ['Organization', 'WebSite'],
  courses: ['ItemList', 'BreadcrumbList'],
  course: ['Course', 'BreadcrumbList'],
  about: ['Organization', 'BreadcrumbList'],
  faq: ['FAQPage', 'BreadcrumbList'],
  privacy: ['BreadcrumbList'],
  security: ['BreadcrumbList'],
})

const COURSE_MODES = new Set(['Onsite', 'Online', 'Blended'])

function isAbsoluteUrl(value) {
  if (typeof value !== 'string') return false
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

function nonEmpty(value) {
  return typeof value === 'string' && value.trim() !== ''
}

function walkKeys(value, path, problems) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => walkKeys(item, `${path}[${index}]`, problems))
    return
  }
  if (value === null || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    if (!ALLOWED_KEYS.has(key)) problems.push(`${path}.${key} is not an allowed JSON-LD property`)
    walkKeys(child, `${path}.${key}`, problems)
  }
}

function checkCourse(course, where, problems, { requireInstance }) {
  if (!nonEmpty(course.name)) problems.push(`${where}: Course.name is missing`)
  if (!nonEmpty(course.description)) problems.push(`${where}: Course.description is missing`)
  if (!isAbsoluteUrl(course.url)) problems.push(`${where}: Course.url is not absolute`)
  if (!course.provider || !nonEmpty(course.provider['@id'] ?? course.provider.name)) problems.push(`${where}: Course.provider is missing`)
  if (!requireInstance) return
  const instance = course.hasCourseInstance
  if (!instance || instance['@type'] !== 'CourseInstance') {
    problems.push(`${where}: Course.hasCourseInstance (CourseInstance) is missing`)
    return
  }
  if (!COURSE_MODES.has(instance.courseMode)) problems.push(`${where}: CourseInstance.courseMode is not Onsite, Online or Blended`)
  if (instance.instructor !== undefined && (instance.instructor['@type'] !== 'Person' || !nonEmpty(instance.instructor.name))) {
    problems.push(`${where}: CourseInstance.instructor must be a Person with a name`)
  }
}

function checkNode(node, where, problems) {
  switch (node['@type']) {
    case 'Organization':
      if (!nonEmpty(node.name)) problems.push(`${where}: Organization.name is missing`)
      if (!isAbsoluteUrl(node.url)) problems.push(`${where}: Organization.url is not absolute`)
      if (!node.logo || !isAbsoluteUrl(node.logo.url ?? node.logo)) problems.push(`${where}: Organization.logo is missing`)
      if (!Array.isArray(node.sameAs) || !node.sameAs.every(isAbsoluteUrl)) problems.push(`${where}: Organization.sameAs must be absolute URLs`)
      break
    case 'WebSite':
      if (!isAbsoluteUrl(node.url)) problems.push(`${where}: WebSite.url is not absolute`)
      if (!nonEmpty(node.name)) problems.push(`${where}: WebSite.name is missing`)
      if (!nonEmpty(node.inLanguage)) problems.push(`${where}: WebSite.inLanguage is missing`)
      break
    case 'ItemList':
      if (!Array.isArray(node.itemListElement) || node.itemListElement.length === 0) {
        problems.push(`${where}: ItemList.itemListElement is empty`)
        break
      }
      node.itemListElement.forEach((element, index) => {
        if (element['@type'] !== 'ListItem' || element.position !== index + 1) problems.push(`${where}: ItemList element ${index + 1} has a wrong type or position`)
        if (!element.item || element.item['@type'] !== 'Course') problems.push(`${where}: ItemList element ${index + 1} is not a Course`)
        else checkCourse(element.item, `${where} item ${index + 1}`, problems, { requireInstance: false })
      })
      if (node.numberOfItems !== node.itemListElement.length) problems.push(`${where}: ItemList.numberOfItems does not match`)
      break
    case 'Course':
      checkCourse(node, where, problems, { requireInstance: true })
      break
    case 'BreadcrumbList':
      if (!Array.isArray(node.itemListElement) || node.itemListElement.length < 2) {
        problems.push(`${where}: BreadcrumbList needs at least two items`)
        break
      }
      node.itemListElement.forEach((element, index) => {
        if (element.position !== index + 1 || !nonEmpty(element.name) || !isAbsoluteUrl(element.item)) {
          problems.push(`${where}: BreadcrumbList item ${index + 1} needs position, name and an absolute item URL`)
        }
      })
      break
    case 'FAQPage':
      if (!Array.isArray(node.mainEntity) || node.mainEntity.length === 0) {
        problems.push(`${where}: FAQPage.mainEntity is empty`)
        break
      }
      node.mainEntity.forEach((question, index) => {
        if (question['@type'] !== 'Question' || !nonEmpty(question.name) || question.acceptedAnswer?.['@type'] !== 'Answer' || !nonEmpty(question.acceptedAnswer?.text)) {
          problems.push(`${where}: FAQPage question ${index + 1} needs name and acceptedAnswer.text`)
        }
      })
      break
    default:
      problems.push(`${where}: unexpected @type ${JSON.stringify(node['@type'])}`)
  }
}

/**
 * Validates the raw text of a page's ld+json blocks: exactly one block, valid JSON, schema.org @graph, the
 * required types for `kind`, required properties per type, allowed property names only, no raw "<" (so the
 * text can never close the <script> element).
 */
export function validateJsonLdBlocks(rawBlocks, kind, where = kind) {
  const problems = []
  if (rawBlocks.length !== 1) {
    problems.push(`${where}: expected exactly one JSON-LD block, found ${rawBlocks.length}`)
    return { problems, types: [] }
  }
  const raw = rawBlocks[0]
  if (raw.includes('<')) problems.push(`${where}: JSON-LD contains a raw "<" (must be escaped as \\u003c)`)
  let data
  try {
    data = JSON.parse(raw)
  } catch (error) {
    problems.push(`${where}: JSON-LD is not valid JSON (${error instanceof Error ? error.message : String(error)})`)
    return { problems, types: [] }
  }
  if (data['@context'] !== 'https://schema.org' || !Array.isArray(data['@graph'])) {
    problems.push(`${where}: JSON-LD must be {"@context":"https://schema.org","@graph":[...]}`)
    return { problems, types: [] }
  }
  walkKeys(data, where, problems)
  const types = data['@graph'].map(node => node['@type'])
  for (const required of REQUIRED_TYPES[kind] ?? []) {
    if (!types.includes(required)) problems.push(`${where}: JSON-LD has no ${required}`)
  }
  data['@graph'].forEach((node, index) => checkNode(node, `${where} node ${index + 1}`, problems))
  return { problems, types, data }
}

/** The page kind of a locale-neutral public path. */
export function pageKind(neutralPath) {
  if (neutralPath === '/') return 'home'
  if (neutralPath === '/courses') return 'courses'
  if (neutralPath.startsWith('/courses/')) return 'course'
  return neutralPath.slice(1)
}
