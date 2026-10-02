// Length rules of docs/seo/META.md: titles at most 60 characters, descriptions 140-160 characters.

export const TITLE_MAX = 60
export const DESCRIPTION_MIN = 140
export const DESCRIPTION_MAX = 160
export const TITLE_SUFFIX = ' · EduCore'

const ELLIPSIS = '…'

function collapse(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

/** Cuts `text` to at most `max` characters at a word boundary and appends an ellipsis when it was cut. */
export function truncateAtWord(text: string, max: number): string {
  const clean = collapse(text)
  if (clean.length <= max) return clean
  const room = max - ELLIPSIS.length
  const cut = clean.slice(0, room + 1)
  const boundary = cut.lastIndexOf(' ')
  const head = boundary >= room * 0.6 ? cut.slice(0, boundary) : clean.slice(0, room)
  return `${head.replace(/[\s,;:.–—-]+$/u, '')}${ELLIPSIS}`
}

/** "<page> · EduCore", shortening the page part so that the whole title stays within 60 characters. */
export function pageTitle(page: string): string {
  const name = collapse(page)
  if (name.length + TITLE_SUFFIX.length <= TITLE_MAX) return `${name}${TITLE_SUFFIX}`
  return `${truncateAtWord(name, TITLE_MAX - TITLE_SUFFIX.length)}${TITLE_SUFFIX}`
}

/**
 * Joins sentences until the description reaches 140 characters, then cuts it at a word boundary so that it is
 * at most 160. Used for generated descriptions (course pages); hand-written ones are checked by tests instead.
 */
export function fitDescription(parts: readonly string[]): string {
  let text = ''
  for (const part of parts.map(collapse).filter(Boolean)) {
    text = text === '' ? part : `${text} ${part}`
    if (text.length >= DESCRIPTION_MIN) break
  }
  if (text.length > DESCRIPTION_MAX) text = truncateAtWord(text, DESCRIPTION_MAX)
  return text
}

/** Ends a fragment with a full stop unless it already ends with sentence punctuation. */
export function sentence(text: string): string {
  const clean = collapse(text)
  return /[.!?…]$/u.test(clean) ? clean : `${clean}.`
}
