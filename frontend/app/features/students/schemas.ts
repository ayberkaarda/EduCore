import { z } from '../../lib/zod'
import { IPV4_ADDRESS, PERSON_NAME, STUDENT_NUMBER } from '../../lib/validation'

// Mirrors the optional fields in CreateStudentRequest / UpdateStudentRequest.
const personName = (field: string) =>
  z.string()
    .trim()
    .min(1, `Enter the ${field}.`)
    .max(100, `The ${field} is at most 100 characters.`)
    .regex(PERSON_NAME, `The ${field} must start with a letter and contain only letters, spaces, apostrophes, dots and hyphens.`)

const studentNumber = z.string()
  .trim()
  .refine(value => value === '' || STUDENT_NUMBER.test(value), 'The student number has 4 to 12 digits.').default('')

export const createStudentSchema = z.object({
  firstName: personName('first name'),
  lastName: z.string().trim().max(100).refine(value => value === '' || PERSON_NAME.test(value), 'Enter a valid last name.').default(''),
  studentNumber,
})
export type CreateStudentValues = z.infer<typeof createStudentSchema>

export const updateStudentSchema = z.object({
  firstName: personName('first name'),
  lastName: z.string().trim().max(100).refine(value => value === '' || PERSON_NAME.test(value), 'Enter a valid last name.').default(''),
  studentNumber,
  ipAddress: z.string()
    .trim()
    .refine(value => value === '' || IPV4_ADDRESS.test(value), 'Enter an IPv4 address such as 192.168.1.5, or leave it empty.'),
})
export type UpdateStudentValues = z.infer<typeof updateStudentSchema>
