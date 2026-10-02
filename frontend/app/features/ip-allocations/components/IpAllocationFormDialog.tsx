import { zodResolver } from '@hookform/resolvers/zod'
import { useForm, useWatch } from 'react-hook-form'
import Dialog from '../../../components/Dialog'
import TextField from '../../../components/TextField'
import { toApiError } from '../../../lib/api-error'
import { describeApiError, toastApiError } from '../../../lib/error-messages'
import { ipAllocationFormSchema, toIpAllocationInput, type IpAllocationFormValues } from '../schemas'

interface IpAllocationFormDialogProps {
  onSubmit: (body: ReturnType<typeof toIpAllocationInput>) => Promise<unknown>
  onCancel: () => void
}

export default function IpAllocationFormDialog({ onSubmit, onCancel }: IpAllocationFormDialogProps) {
  const { register, control, handleSubmit, reset, setError, formState: { errors, isSubmitting } } = useForm<IpAllocationFormValues>({
    resolver: zodResolver(ipAllocationFormSchema),
    defaultValues: { type: 'STATIC', value: '', rangeEnd: '' },
  })
  const type = useWatch({ control, name: 'type' })
  const typeField = register('type')

  const submit = handleSubmit(async values => {
    try {
      await onSubmit(toIpAllocationInput(values))
    } catch (error) {
      const apiError = toApiError(error)
      if (apiError.code === 'ip-allocation/invalid' || apiError.codesFor('originalValue').length > 0) {
        setError('value', { type: 'server', message: describeApiError(apiError, 'The value is invalid.') })
      } else {
        toastApiError(apiError, 'Could not add the IP allocation.')
      }
    }
  })

  return (
    <div className="modal-overlay">
      <Dialog className="modal-content">
        <h3>Add rule</h3>
        <form onSubmit={submit} noValidate>
          <div className="form-group">
            <label htmlFor="ip-rule-type">Type (required)</label>
            <select
              id="ip-rule-type"
              {...typeField}
              onChange={event => {
                void typeField.onChange(event)
                reset({ type: event.target.value as IpAllocationFormValues['type'], value: '', rangeEnd: '' })
              }}
            >
              <option value="STATIC">Single address</option>
              <option value="RANGE">Range</option>
              <option value="CIDR">Subnet (CIDR)</option>
            </select>
          </div>

          {type === 'STATIC' && (
            <TextField id="ip-rule-address" label="IP address (required)" placeholder="e.g. 192.168.1.5" inputClassName="mono-input"
              registration={register('value')} error={errors.value?.message} />
          )}
          {type === 'RANGE' && (
            <div className="inline-fields">
              <TextField id="ip-rule-start" className="form-group flex-field" label="Start address (required)" placeholder="e.g. 192.168.1.1"
                inputClassName="mono-input" registration={register('value')} error={errors.value?.message} />
              <TextField id="ip-rule-end" className="form-group flex-field" label="End address (required)" placeholder="e.g. 192.168.1.255"
                inputClassName="mono-input" registration={register('rangeEnd')} error={errors.rangeEnd?.message} />
            </div>
          )}
          {type === 'CIDR' && (
            <TextField id="ip-rule-cidr" label="Subnet (CIDR) (required)" placeholder="e.g. 192.168.1.0/24" inputClassName="mono-input"
              registration={register('value')} error={errors.value?.message} />
          )}

          <div className="modal-actions">
            <button type="button" className="btn-secondary" disabled={isSubmitting} onClick={onCancel}>Cancel</button>
            <button type="submit" className="btn-primary" disabled={isSubmitting}>{isSubmitting ? 'Saving…' : 'Save rule'}</button>
          </div>
        </form>
      </Dialog>
    </div>
  )
}
