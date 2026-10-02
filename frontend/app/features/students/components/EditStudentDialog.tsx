import type { z } from '../../../lib/zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import Dialog from '../../../components/Dialog'
import TextField from '../../../components/TextField'
import { applyFieldErrors, toastApiError } from '../../../lib/error-messages'
import type { Account } from '../../../lib/types'
import { useIpAllocations } from '../../ip-allocations/hooks'
import { useUpdateStudent } from '../hooks'
import { updateStudentSchema, type UpdateStudentValues } from '../schemas'

const FIELDS = ['firstName', 'lastName', 'studentNumber', 'ipAddress'] as const

interface EditStudentDialogProps {
  student: Account
  /** Rows of the current page, used to mark single addresses already assigned to someone else. */
  pageStudents: Account[]
  onSaved: () => void
  onCancel: () => void
}

export default function EditStudentDialog({ student, pageStudents, onSaved, onCancel }: EditStudentDialogProps) {
  const ipRules = useIpAllocations()
  const updateStudent = useUpdateStudent()
  const [ipInputMode, setIpInputMode] = useState<'manual' | 'select'>('manual')
  const [poolHint, setPoolHint] = useState('')
  const { register, control, handleSubmit, setValue, setError, formState: { errors, isSubmitting } } = useForm<z.input<typeof updateStudentSchema>, unknown, UpdateStudentValues>({
    resolver: zodResolver(updateStudentSchema),
    defaultValues: {
      firstName: student.firstName,
      lastName: student.lastName ?? '',
      studentNumber: student.studentNumber ?? '',
      ipAddress: student.ipAddress ?? '',
    },
  })
  const ipAddress = useWatch({ control, name: 'ipAddress' })

  useEffect(() => {
    if (ipRules.error) toastApiError(ipRules.error, 'Could not load IP allocations.')
  }, [ipRules.error])

  const submit = handleSubmit(async values => {
    try {
      await updateStudent.mutateAsync({ id: student.id, body: values })
      onSaved()
    } catch (error) {
      if (!applyFieldErrors(error, setError, FIELDS)) toastApiError(error, 'Failed to update student.')
    }
  })

  const rules = ipRules.data ?? []
  const selectSource = (selected: string) => {
    setPoolHint('')
    if (selected === 'manual') {
      setIpInputMode('manual')
      setValue('ipAddress', '')
      return
    }
    const rule = rules.find(item => item.originalValue === selected)
    if (rule?.type === 'STATIC') {
      setIpInputMode('select')
      setValue('ipAddress', selected, { shouldValidate: true })
    } else {
      // A range or subnet: start from its first address and let the admin enter one address of the pool.
      setIpInputMode('manual')
      setValue('ipAddress', selected.split(/[-/]/)[0])
      setPoolHint('Enter a single address from the selected pool.')
    }
  }

  return (
    <div className="modal-overlay">
      <Dialog className="modal-content">
        <h3>Edit student</h3>
        <form onSubmit={submit} noValidate>
          <TextField id="student-edit-first-name" label="First name (required)" autoComplete="off" registration={register('firstName')} error={errors.firstName?.message} />
          <TextField id="student-edit-last-name" label="Last name (optional)" autoComplete="off" registration={register('lastName')} error={errors.lastName?.message} />
          <TextField id="student-edit-number" label="Student number (optional)" inputMode="numeric" inputClassName="mono-input"
            registration={register('studentNumber')} error={errors.studentNumber?.message} />

          <div className="form-group">
            <label htmlFor="student-edit-ip-source">IP source</label>
            <select id="student-edit-ip-source" value={ipInputMode === 'manual' ? 'manual' : (ipAddress || 'manual')} onChange={event => selectSource(event.target.value)}>
              <option value="manual">Manual</option>
              {rules.map(rule => {
                const isStatic = rule.type === 'STATIC'
                const isLocked = isStatic && pageStudents.some(other => other.ipAddress === rule.originalValue && other.id !== student.id)
                return (
                  <option key={rule.id} value={rule.originalValue} disabled={isLocked}>
                    {rule.originalValue} ({isStatic ? 'Single address' : 'Pool'}){isStatic ? (isLocked ? ' — Assigned' : ' — Available') : ''}
                  </option>
                )
              })}
            </select>
            {poolHint && <p className="field-hint">{poolHint}</p>}
          </div>
          {ipInputMode === 'manual' && (
            <TextField id="student-edit-ip" label="IP address" placeholder="e.g. 192.168.1.5" inputClassName="mono-input"
              registration={register('ipAddress')} error={errors.ipAddress?.message} />
          )}
          {ipInputMode === 'select' && errors.ipAddress && <p className="field-error">{errors.ipAddress.message}</p>}
          <div className="modal-actions">
            <button type="button" className="btn-secondary" disabled={isSubmitting} onClick={onCancel}>Cancel</button>
            <button type="submit" className="btn-primary" disabled={isSubmitting}>{isSubmitting ? 'Saving…' : 'Save changes'}</button>
          </div>
        </form>
      </Dialog>
    </div>
  )
}
