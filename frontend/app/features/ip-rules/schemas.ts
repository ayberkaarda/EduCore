import { z } from '../../lib/zod'
import { SINGLE_LINE_TEXT } from '../../lib/validation'
import { ipAllocationFormSchema } from '../ip-allocations/schemas'
export const denyRuleSchema = z.object({
  kind: z.enum(['STATIC', 'RANGE', 'CIDR']),
  value: z.string().trim().min(1, 'Enter an IPv4 definition.').max(43).regex(/^[a-fA-F0-9.:/-]+$/, 'Enter an IPv4 definition.'),
  reason: z.string().max(200).regex(SINGLE_LINE_TEXT, 'Enter a single line without control characters.'),
  expiresAt: z.string().refine(value => !value || (/T.*(Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value)) && Date.parse(value) > Date.now()), 'Choose an ISO expiry with offset in the future.'),
}).superRefine(({ kind, value }, context) => {
  if (value.includes(':')) {
    context.addIssue({ code: 'custom', path: ['value'], message: 'IPv6 deny rules are not supported. Enter an IPv4 definition.' })
    return
  }
  if (kind === 'RANGE' && value.split('-').length !== 2) {
    context.addIssue({ code: 'custom', path: ['value'], message: 'Enter exactly two IPv4 addresses separated by a hyphen.' })
    return
  }
  const [start, end = ''] = kind === 'RANGE' ? value.split('-') : [value, '']
  const parsed = ipAllocationFormSchema.safeParse({ type: kind, value: start, rangeEnd: end })
  if (!parsed.success) context.addIssue({ code: 'custom', path: ['value'], message: parsed.error.issues[0].message })
})
export type DenyRuleValues = z.infer<typeof denyRuleSchema>
