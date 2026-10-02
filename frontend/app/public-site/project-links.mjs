// External links stated on the public pages, in JSON-LD (Organization.sameAs) and in llms.txt. Types:
// project-links.d.mts.

export const SOURCE_REPOSITORY_URL = 'https://github.com/ayberkaarda/EduCore'
export const VULNERABILITY_REPORT_URL = `${SOURCE_REPOSITORY_URL}/security/advisories/new`

/** The Contact section of llms.txt. */
export const CONTACT_LINKS = Object.freeze([
  Object.freeze({
    title: 'Source repository',
    url: SOURCE_REPOSITORY_URL,
    summary: 'EduCore source code, documentation and change history.',
  }),
  Object.freeze({
    title: 'Report a security vulnerability',
    url: VULNERABILITY_REPORT_URL,
    summary: 'Private vulnerability report to the maintainers (GitHub private vulnerability reporting).',
  }),
])
