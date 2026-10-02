import { expect, test } from '@playwright/test'
import { ADMIN, createCourse, createStudent, deleteCourse, enrollmentsOf, uniqueLetters, uniqueSuffix } from './support/api'
import { signIn } from './support/ui'

test('ADMIN enrols a student into a course and drops the enrolment', async ({ page }) => {
  const lastName = `Enrolee ${uniqueLetters()}`
  const student = await createStudent('Course', lastName)
  const course = await createCourse(`E2E Enrolment ${uniqueSuffix()}`)

  try {
    await signIn(page, ADMIN.username, ADMIN.password)
    await page.getByRole('navigation', { name: 'Main navigation' }).getByRole('link', { name: 'Students' }).click()
    await page.getByRole('textbox', { name: 'Search by name or number' }).fill(lastName)
    const row = page.getByRole('row').filter({ hasText: lastName })
    await expect(row).toHaveCount(1)
    await row.getByRole('button', { name: 'Courses' }).click()
    await expect(page).toHaveURL(new RegExp(`/app/students/${student.id}$`))
    await expect(page.getByRole('heading', { name: 'Student courses' })).toBeVisible()
    await expect(page.getByText('The student has not enrolled in any courses yet.')).toBeVisible()

    const catalogueItem = page.locator('.course-item').filter({ has: page.getByRole('heading', { name: course.name, exact: true }) })
      .filter({ has: page.getByRole('button', { name: 'Enrol' }) })
    await catalogueItem.getByRole('button', { name: 'Enrol' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Course added.' })).toBeVisible()
    const drop = page.getByRole('button', { name: `Drop ${course.name}` })
    await expect(drop).toBeVisible()
    await expect(page.locator('.course-item').filter({ hasText: course.name }).getByRole('button', { name: 'Enrolled' })).toBeDisabled()
    expect((await enrollmentsOf(student.id)).map(item => item.id)).toEqual([course.id])

    await drop.click()
    const confirmation = page.getByRole('dialog', { name: `Drop the course '${course.name}' for this student?` })
    await confirmation.getByRole('button', { name: 'Drop course' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Course dropped.' })).toBeVisible()
    await expect(drop).toBeHidden()
    await expect(page.getByText('The student has not enrolled in any courses yet.')).toBeVisible()
    expect(await enrollmentsOf(student.id)).toEqual([])
  } finally {
    await deleteCourse(course.id)
  }
})
