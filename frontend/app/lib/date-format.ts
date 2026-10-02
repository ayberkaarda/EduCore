export function formatAccountDate(value: string) {
  return new Intl.DateTimeFormat(typeof navigator === 'undefined' ? 'en-GB' : navigator.language, { dateStyle: 'long', timeStyle: 'short' }).format(new Date(value))
}
