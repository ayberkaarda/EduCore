import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { ApiError, CANCELED_ERROR_CODE, NETWORK_ERROR_CODE, toApiError } from './api-error'
import { toast } from './toast'

/** User-facing sentences for the stable problem codes in docs/api/ROUTES.md (section "Errors"). */
const CODE_MESSAGES: Record<string, string> = {
  'ip-allocation/invalid': 'Enter a valid IPv4 allocation. CIDR blocks must use the network address with no host bits set.',
  'ip-allocation/not-found': 'The IP allocation no longer exists.',
  'ip-rule/ipv6-unsupported': 'IPv6 deny rules are not supported. Enter an IPv4 definition.',
  'ip-rule/self-deny': 'This rule would block your current IP address. Choose a different range.',
  'ip-rule/trusted-proxy': 'This rule would block a trusted proxy. Choose a different range.',
  'webhook/invalid-url': 'Enter an allowed absolute HTTPS URL without credentials or a fragment.',
  'webhook/limit-reached': 'The limit of 20 subscriptions has been reached. Delete a subscription before adding another.',
  'webhook/not-found': 'The webhook no longer exists.',
  'webhook/queue-full': 'The delivery queue is full. Wait for pending deliveries before sending another test event.',
  'webhook/too-many-test-events': 'Too many test events. Wait a minute before trying again.',
  'import/file-too-large': 'The CSV file must be 5 MB or smaller.',
  'import/invalid-file-name': 'Choose a file with a valid .csv filename.',
  'import/empty-file': 'Choose a non-empty CSV file.',
  'import/not-text': 'The file contains binary data. Upload a text CSV file.',
  'import/not-utf8': 'Save the CSV file as UTF-8 and upload it again.',
  'import/invalid-line-break': 'Use consistent CSV line breaks and upload the file again.',
  'import/record-too-long': 'A CSV record is too long. Shorten it and upload the file again.',
  'import/invalid-header': 'The CSV header is invalid. Use the required student or course columns.',
  'import/no-data-rows': 'Add at least one data row after the CSV header.',
  'import/too-many-rows': 'The CSV contains too many rows. Split it into smaller files.',
  'import/duplicate': 'This file has already been imported. Check job logs for its result.',
  'job-log/not-found': 'The job log no longer exists.',
  [NETWORK_ERROR_CODE]: 'The server is unreachable. Check your connection and try again.',
  'auth/invalid-credentials': 'The username or password is incorrect.',
  'auth/access-denied': 'You do not have permission to do this.',
  'auth/origin-rejected': 'This page is not allowed to manage your session. Open EduCore from its usual address.',
  'auth/invalid-current-password': 'The current password is incorrect.',
  'auth/password-policy': 'The new password does not meet the password rules.',
  'auth/unauthenticated': 'Your session has ended. Sign in again.',
  'auth/invalid-refresh-token': 'Your session has ended. Sign in again.',
  'account/not-found': 'The account no longer exists.',
  'account/confirmation-mismatch': 'The username does not match. Enter the exact username to delete permanently.',
  'account/not-restorable': 'This account can no longer be restored.',
  'course/not-found': 'The course no longer exists.',
  'ip-rule/not-found': 'The IP rule no longer exists.',
  'account/self-role-change': 'You cannot change your own role.',
  'account/self-delete': 'You cannot delete your own account.',
  'account/last-admin': 'At least one administrator must remain.',
  'account/student-number-taken': 'This student number is already in use.',
  'account/username-taken': 'This username is already in use.',
  'account/ip-address-taken': 'This IP address is already assigned to another student.',
  'account/ip-address-not-allocatable': 'The IP address is outside every IP allocation.',
  'account/ip-address-invalid': 'Enter an IPv4 address such as 192.168.1.5.',
  'ip-rule/invalid': 'The value does not match the selected rule type, or the range ends before it starts.',
  'enrollment/already-enrolled': 'The student is already enrolled in this course.',
  'request/concurrent-modification': 'Someone else changed this record first. Reload the page and try again.',
  'request/conflict': 'The change conflicts with existing records (for example, a course that still has enrolments or a duplicate name).',
  'request/payload-too-large': 'The upload is too large.',
  'sort/invalid': 'The requested sort order is not supported.',
}

/** Turns any API failure into one sentence for a toast or an inline message. */
export function describeApiError(error: unknown, fallback: string): string {
  const apiError = toApiError(error)
  if (apiError.code === 'auth/account-locked' || apiError.code === 'auth/too-many-attempts') {
    const base = apiError.code === 'auth/account-locked'
      ? 'Too many failed sign-ins. The account is locked for now.'
      : 'Too many sign-in attempts.'
    return apiError.retryAfterSeconds !== undefined
      ? `${base} Try again in ${Math.ceil(apiError.retryAfterSeconds)} seconds.`
      : `${base} Try again later.`
  }
  const known = CODE_MESSAGES[apiError.code]
  if (apiError.code === 'rate-limit/exceeded') return apiError.retryAfterSeconds !== undefined
    ? `Too many requests. Try again in ${apiError.retryAfterSeconds} seconds.`
    : 'Too many requests. Try again later.'
  if (known) return known
  if (apiError.status >= 500) {
    return apiError.correlationId
      ? `The server could not complete the request (reference ${apiError.correlationId}).`
      : 'The server could not complete the request.'
  }
  if (apiError.status === 400 && apiError.fieldErrors.length > 0) {
    return `${fallback} Check: ${[...new Set(apiError.fieldErrors.map(item => item.field))].join(', ')}.`
  }
  return fallback
}

/** Shows an error toast for a failed call; canceled requests (superseded queries) stay silent. */
export function toastApiError(error: unknown, fallback: string): void {
  const apiError = toApiError(error)
  if (apiError.code === CANCELED_ERROR_CODE || apiError.code === 'account/pending-deletion' || apiError.code === 'account/password-change-required') return
  toast.error(describeApiError(apiError, fallback))
}

const FIELD_CODE_MESSAGES: Record<string, string> = {
  required: 'This field is required.',
  size: 'The value is too long or too short.',
  pattern: 'The value has an invalid format.',
  range: 'The value is out of range.',
  type: 'The value has the wrong type.',
  enum: 'Choose one of the listed values.',
  malformed: 'The value is malformed.',
  invalid: 'The value is invalid.',
}

/**
 * Copies a 400 problem's `errors[{field, code}]` onto react-hook-form fields. Returns true when at least one
 * field of the form received a message (the caller can then skip the generic toast).
 */
export function applyFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
): boolean {
  if (!(error instanceof ApiError) || error.status !== 400) return false
  let applied = false
  for (const { field, code } of error.fieldErrors) {
    if ((fields as readonly string[]).includes(field)) {
      setError(field as Path<T>, { type: 'server', message: FIELD_CODE_MESSAGES[code] ?? FIELD_CODE_MESSAGES.invalid })
      applied = true
    }
  }
  return applied
}
