import { z } from '../../lib/zod'
import { SINGLE_LINE_TEXT } from '../../lib/validation'
const date = z.string().refine(value => !value || (/T.*(Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value))), 'Enter an ISO date-time with offset.')
export const jobLogFilterSchema = z.object({ status: z.enum(['', 'SUCCEEDED', 'PARTIAL', 'FAILED']), from: date, to: date, file: z.string().max(100).regex(SINGLE_LINE_TEXT), size: z.string().refine(value => /^(20|50|100)$/.test(value), 'Choose a page size.') }).superRefine(({ from, to }, context) => {
  if (from && to && Date.parse(from) > Date.parse(to)) context.addIssue({ code: 'custom', path: ['from'], message: 'From must be before or equal to To.' })
})
export type JobLogFilterValues = z.infer<typeof jobLogFilterSchema>
