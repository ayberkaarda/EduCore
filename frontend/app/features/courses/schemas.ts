import { z } from '../../lib/zod'
import { SINGLE_LINE_TEXT } from '../../lib/validation'

// Mirrors CourseRequest: name required ≤ 150 (unique), term ≤ 50, instructor ≤ 100, single-line text.
const singleLine = (field: string, max: number) =>
  z.string()
    .regex(SINGLE_LINE_TEXT, `${field} must be a single line without control characters.`)
    .trim()
    .max(max, `${field} is at most ${max} characters.`)

export const courseSchema = z.object({
  name: singleLine('Course name', 150).min(1, 'Enter the course name.'),
  term: singleLine('Term', 50).default(''),
  instructor: singleLine('Instructor', 100).default(''),
})
export type CourseValues = z.infer<typeof courseSchema>
