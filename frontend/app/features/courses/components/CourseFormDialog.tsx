import type { z } from '../../../lib/zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import Dialog from '../../../components/Dialog'
import TextField from '../../../components/TextField'
import { applyFieldErrors, toastApiError } from '../../../lib/error-messages'
import { courseSchema, type CourseValues } from '../schemas'

const FIELDS = ['name', 'term', 'instructor'] as const

interface CourseFormDialogProps {
  title: string
  submitLabel: string
  idPrefix: string
  initialValues: CourseValues
  onSubmit: (values: CourseValues) => Promise<unknown>
  onCancel: () => void
  failureMessage: string
}

export default function CourseFormDialog({ title, submitLabel, idPrefix, initialValues, onSubmit, onCancel, failureMessage }: CourseFormDialogProps) {
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm<z.input<typeof courseSchema>, unknown, CourseValues>({
    resolver: zodResolver(courseSchema),
    defaultValues: initialValues,
  })
  const submit = handleSubmit(async values => {
    try {
      await onSubmit(values)
    } catch (error) {
      if (!applyFieldErrors(error, setError, FIELDS)) toastApiError(error, failureMessage)
    }
  })
  return (
    <div className="modal-overlay">
      <Dialog className="modal-content">
        <h3>{title}</h3>
        <form onSubmit={submit} noValidate>
          <TextField id={`${idPrefix}-name`} label="Course name (required)" registration={register('name')} error={errors.name?.message} />
          <TextField id={`${idPrefix}-term`} label="Term (optional)" registration={register('term')} error={errors.term?.message} />
          <TextField id={`${idPrefix}-instructor`} label="Instructor" placeholder="e.g. Ayberk Arda" registration={register('instructor')} error={errors.instructor?.message} />
          <div className="modal-actions">
            <button type="button" className="btn-secondary" disabled={isSubmitting} onClick={onCancel}>Cancel</button>
            <button type="submit" className="btn-primary" disabled={isSubmitting}>{isSubmitting ? 'Saving…' : submitLabel}</button>
          </div>
        </form>
      </Dialog>
    </div>
  )
}
