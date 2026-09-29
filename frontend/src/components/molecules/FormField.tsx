import { cloneElement, isValidElement, useId, type ReactElement, type ReactNode } from 'react'
import { cn } from '@/lib/cn'

export interface FormFieldProps {
  label: string
  /** Error message shown below the control. Marks the control as invalid. */
  error?: string | null
  hint?: string
  required?: boolean
  /** Optional id; generated when omitted. Forwarded to the child control. */
  id?: string
  className?: string
  children: ReactNode
}

interface ControlProps {
  id?: string
  invalid?: boolean
  'aria-describedby'?: string
  required?: boolean
}

/**
 * Wraps a form control with a label, optional hint and error message.
 * Injects `id`, `invalid` and `aria-describedby` into a single child element.
 */
export function FormField({ label, error, hint, required, id, className, children }: FormFieldProps) {
  const generatedId = useId()
  const controlId = id ?? generatedId
  const hintId = `${controlId}-hint`
  const errorId = `${controlId}-error`

  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined

  const control =
    isValidElement<ControlProps>(children) && typeof children.type !== 'string'
      ? cloneElement(children as ReactElement<ControlProps>, {
          id: controlId,
          invalid: !!error,
          'aria-describedby': describedBy,
        })
      : isValidElement<ControlProps>(children)
        ? cloneElement(children as ReactElement<ControlProps>, { id: controlId, 'aria-describedby': describedBy })
        : children

  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <label htmlFor={controlId} className="text-sm font-medium text-stone-800">
        {label}
        {required && (
          <span className="ml-0.5 text-brand-600" aria-hidden="true">
            *
          </span>
        )}
      </label>
      {control}
      {hint && !error && (
        <p id={hintId} className="text-xs text-stone-500">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className="text-xs font-medium text-brand-600">
          {error}
        </p>
      )}
    </div>
  )
}
