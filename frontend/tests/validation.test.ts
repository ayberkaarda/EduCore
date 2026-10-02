import { describe, expect, it } from 'vitest'
import { courseSchema } from '../app/features/courses/schemas'
import { createStudentSchema, updateStudentSchema } from '../app/features/students/schemas'
import { SINGLE_LINE_TEXT } from '../app/lib/validation'

describe('contract validation', () => {
  it('accepts absent and empty optional student fields on create and edit', () => {
    expect(createStudentSchema.parse({ firstName: 'Ada' })).toEqual({ firstName: 'Ada', lastName: '', studentNumber: '' })
    expect(updateStudentSchema.safeParse({ firstName: 'Ada', lastName: '', studentNumber: '', ipAddress: '' }).success).toBe(true)
    expect(createStudentSchema.safeParse({ firstName: 'Ada', studentNumber: '1234' }).success).toBe(true)
    expect(createStudentSchema.safeParse({ firstName: 'Ada', studentNumber: '123' }).success).toBe(false)
    expect(createStudentSchema.safeParse({ firstName: 'Ada', studentNumber: '1234567890123' }).success).toBe(false)
  })
  it('allows an absent or empty course term', () => {
    expect(courseSchema.safeParse({ name: 'Math' }).success).toBe(true)
    expect(courseSchema.safeParse({ name: 'Math', term: '', instructor: '' }).success).toBe(true)
    expect(courseSchema.safeParse({ name: 'Math', term: 'a'.repeat(50) }).success).toBe(true)
    expect(courseSchema.safeParse({ name: 'Math', term: 'a'.repeat(51) }).success).toBe(false)
    expect(courseSchema.safeParse({ name: 'Math', term: '\u2028' }).success).toBe(false)
  })
  it.each(['\u0000', '\u001f', '\u007f', '\u0085', '\u009f', '\u200b', '\u202e', '\u2028', '\u2029', '\ud800', '\udfff'])('rejects forbidden character %j anywhere', character => {
    expect(SINGLE_LINE_TEXT.test(character)).toBe(false)
    expect(SINGLE_LINE_TEXT.test(`a${character}b`)).toBe(false)
  })
  it.each(['', 'ordinary text', '\u00a0', '\u0301', '😀'])('accepts permitted text %j', value => {
    expect(SINGLE_LINE_TEXT.test(value)).toBe(true)
  })
})