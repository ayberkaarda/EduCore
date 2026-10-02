import { z } from '../../lib/zod'

// Mirrors LoginRequest / PasswordChangeRequest and PasswordPolicy on the backend (docs/api/ROUTES.md).

const CONTROL_CHARACTERS = /\p{Cc}/u

export const PASSWORD_MIN_LENGTH = 12
export const PASSWORD_MAX_LENGTH = 128
export const PASSWORD_MAX_BYTES = 72

export const loginSchema = z.object({
  username: z.string()
    .trim()
    .min(1, 'Enter your username.')
    .max(255, 'The username is at most 255 characters.')
    .refine(value => !CONTROL_CHARACTERS.test(value), 'The username cannot contain control characters.'),
  password: z.string().min(1, 'Enter your password.').max(PASSWORD_MAX_LENGTH, `The password is at most ${PASSWORD_MAX_LENGTH} characters.`),
})
export type LoginValues = z.infer<typeof loginSchema>

/** User-facing text for each policy rule the backend can report in `violations`. */
export const POLICY_MESSAGES: Record<string, string> = {
  too_short: `Use at least ${PASSWORD_MIN_LENGTH} characters.`,
  too_long: `Use at most ${PASSWORD_MAX_LENGTH} characters.`,
  too_many_bytes: `The password is too long when encoded (at most ${PASSWORD_MAX_BYTES} bytes; letters with accents and emoji count as several).`,
  blank: 'The password cannot consist of spaces only.',
  common_password: 'This password is too common. Choose a less predictable one.',
  same_as_current: 'The new password must differ from the current password.',
}

const codePoints = (value: string) => [...value].length
const utf8Bytes = (value: string) => new TextEncoder().encode(value).length

/** The client-side subset of the policy (the deny list is checked by the server only). */
export function localPolicyViolations(newPassword: string, currentPassword: string): string[] {
  const violations: string[] = []
  const length = codePoints(newPassword)
  if (length < PASSWORD_MIN_LENGTH) violations.push('too_short')
  if (length > PASSWORD_MAX_LENGTH) violations.push('too_long')
  else if (utf8Bytes(newPassword) > PASSWORD_MAX_BYTES) violations.push('too_many_bytes')
  if (newPassword.length > 0 && newPassword.trim() === '') violations.push('blank')
  if (newPassword.length > 0 && newPassword === currentPassword) violations.push('same_as_current')
  return violations
}

export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Enter your current password.').max(PASSWORD_MAX_LENGTH, `The password is at most ${PASSWORD_MAX_LENGTH} characters.`),
    newPassword: z.string().min(1, 'Enter a new password.'),
    confirmPassword: z.string().min(1, 'Repeat the new password.'),
  })
  .superRefine((values, context) => {
    for (const violation of localPolicyViolations(values.newPassword, values.currentPassword)) {
      context.addIssue({ code: 'custom', path: ['newPassword'], message: POLICY_MESSAGES[violation] })
    }
    if (values.confirmPassword && values.confirmPassword !== values.newPassword) {
      context.addIssue({ code: 'custom', path: ['confirmPassword'], message: 'The passwords do not match.' })
    }
  })
export type ChangePasswordValues = z.infer<typeof changePasswordSchema>
