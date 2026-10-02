import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import EnrollmentBoard from '../app/features/enrollments/components/EnrollmentBoard'

it('distinguishes loading, failed and loaded-empty enrollments and gates actions', async () => {
  const onRetry = vi.fn()
  const props = { enrolled: [], catalogue: [{ id: 1, name: 'Math', term: '', instructor: null }], busy: false, emptyEnrolledText: 'No enrollments.', actionLabel: 'Select', doneLabel: 'Selected', onEnroll: vi.fn(), onRetry }
  const view = render(<EnrollmentBoard {...props} loading />)
  expect(screen.getByRole('status')).toHaveTextContent('Loading enrollments')
  expect(screen.getByRole('button', { name: 'Select' })).toBeDisabled()
  expect(screen.queryByText('No enrollments.')).not.toBeInTheDocument()
  view.rerender(<EnrollmentBoard {...props} error />)
  expect(screen.getByRole('alert')).toHaveTextContent('could not be loaded')
  expect(screen.getByRole('button', { name: 'Select' })).toBeDisabled()
  await userEvent.setup().click(screen.getByRole('button', { name: 'Retry' }))
  expect(onRetry).toHaveBeenCalledOnce()
  view.rerender(<EnrollmentBoard {...props} />)
  expect(screen.getByText('No enrollments.')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Select' })).toBeEnabled()
})