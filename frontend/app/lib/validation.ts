// Client mirrors of com.educore.common.validation.InputPatterns. The server stays authoritative.

const OCTET = '(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])'
const IPV4 = `${OCTET}\\.${OCTET}\\.${OCTET}\\.${OCTET}`

/** Dotted-quad IPv4 address without leading zeros. */
export const IPV4_ADDRESS = new RegExp(`^${IPV4}$`)
/** 4 to 12 digits. */
export const STUDENT_NUMBER = /^[0-9]{4,12}$/
/** Starts with a letter; then letters, combining marks, spaces, apostrophes, dots and hyphens. */
export const PERSON_NAME = /^\p{L}[\p{L}\p{M} '.-]*$/u
/** One line of text: no control characters. */
export const SINGLE_LINE_TEXT = /^[^\p{Cc}\p{Cf}\p{Zl}\p{Zp}\p{Cs}\u0085]*$/u
/** CIDR prefix 0 to 32 without leading zeros. */
export const CIDR_PREFIX = /^(3[0-2]|[12]?[0-9])$/

/** IPv4 address as an unsigned 32-bit number (input must match IPV4_ADDRESS). */
export function ipv4ToNumber(address: string): number {
  return address.split('.').reduce((total, octet) => total * 256 + Number(octet), 0)
}
