import type { z } from '../../../lib/zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import Dialog from '../../../components/Dialog'
import TextField from '../../../components/TextField'
import { applyFieldErrors, toastApiError } from '../../../lib/error-messages'
import type { CreatedStudent } from '../../../lib/types'
import { useCreateStudent } from '../hooks'
import { createStudentSchema, type CreateStudentValues } from '../schemas'

const FIELDS = ['firstName', 'lastName', 'studentNumber'] as const

interface AddStudentDialogProps {
  onCreated: (student: CreatedStudent, submitted: CreateStudentValues) => void
  onCancel: () => void
}

export default function AddStudentDialog({ onCreated, onCancel }: AddStudentDialogProps) {
  const createStudent = useCreateStudent()
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm<z.input<typeof createStudentSchema>, unknown, CreateStudentValues>({
    resolver: zodResolver(createStudentSchema),
    defaultValues: { firstName: '', lastName: '', studentNumber: '' },
  })
  const submit = handleSubmit(async values => {
    try {
      const created = await createStudent.mutateAsync(values)
      onCreated(created, values)
    } catch (error) {
      if (!applyFieldErrors(error, setError, FIELDS)) toastApiError(error, 'Could not add the student.')
    }
  })
  return (
    <div className="modal-overlay">
      <Dialog className="modal-content">
        <h3>Add student</h3>
        <form onSubmit={submit} noValidate>
          <TextField id="student-add-first-name" label="First name (required)" autoComplete="off" registration={register('firstName')} error={errors.firstName?.message} />
          <TextField id="student-add-last-name" label="Last name (optional)" autoComplete="off" registration={register('lastName')} error={errors.lastName?.message} />
          <TextField id="student-add-number" label="Student number (optional)" placeholder="e.g. 2601005" inputMode="numeric" inputClassName="mono-input"
            registration={register('studentNumber')} error={errors.studentNumber?.message} />
          <div className="modal-actions">
            <button type="button" className="btn-secondary" disabled={isSubmitting} onClick={onCancel}>Cancel</button>
            <button type="submit" className="btn-primary" disabled={isSubmitting}>{isSubmitting ? 'Saving…' : 'Save'}</button>
          </div>
        </form>
      </Dialog>
    </div>
  )
}
