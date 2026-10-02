import { z } from '../../lib/zod'
export const deleteAccountSchema = z.object({ currentPassword: z.string().min(1, 'Enter your current password.').max(128, 'Use at most 128 characters.') })
export type DeleteAccountValues = z.infer<typeof deleteAccountSchema>
