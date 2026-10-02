import type { InputHTMLAttributes } from 'react'
import type { UseFormRegisterReturn } from 'react-hook-form'

interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id' | 'name'> {
  id: string
  label: string
  registration: UseFormRegisterReturn
  error?: string
  hint?: string
  className?: string
  inputClassName?: string
}

/** Labelled text input wired to react-hook-form, with its validation message linked via aria-describedby. */
export default function TextField({ id, label, registration, error, hint, className = 'form-group', inputClassName, type = 'text', ...rest }: TextFieldProps) {
  const hintId = hint ? `${id}-hint` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined
  return (
    <div className={className}>
      <label htmlFor={id}>{label}</label>
      <input id={id} type={type} className={inputClassName} aria-invalid={error ? true : undefined} aria-describedby={describedBy} {...rest} {...registration} />
      {hint && <p id={hintId} className="field-hint">{hint}</p>}
      {error && <p id={errorId} className="field-error">{error}</p>}
    </div>
  )
}
