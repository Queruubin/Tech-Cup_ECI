import { forwardRef, useState } from 'react'
import { cn } from '@/lib/cn'
import { Input, type InputProps } from './Input'

export type PasswordInputProps = Omit<InputProps, 'type'>

// Outline eye / eye-slash icons (24px grid, stroke-based like the other inline icons).
const EYE_PATH =
  'M2.036 12.322a1.012 1.012 0 010-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639' +
  'C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178zM15 12a3 3 0 11-6 0 3 3 0 016 0z'
const EYE_SLASH_PATH =
  'M3.98 8.223A10.477 10.477 0 001.934 12C3.226 16.338 7.244 19.5 12 19.5c.993 0 1.953-.138 2.863-.395M6.228 6.228' +
  'A10.45 10.45 0 0112 4.5c4.756 0 8.773 3.162 10.065 7.498a10.523 10.523 0 01-4.293 5.774M6.228 6.228L3 3m3.228 3.228' +
  'l3.65 3.65m7.894 7.894L21 21m-3.228-3.228l-3.65-3.65m0 0a3 3 0 10-4.243-4.243m4.242 4.242L9.88 9.88'

/**
 * Password field with a show/hide toggle. Forwards every prop and the ref to the underlying `Input`,
 * so `FormField` can still inject `id`, `invalid` and `aria-describedby`.
 */
export const PasswordInput = forwardRef<HTMLInputElement, PasswordInputProps>(function PasswordInput(
  { className, disabled, ...rest },
  ref,
) {
  const [visible, setVisible] = useState(false)

  return (
    <div className="relative">
      <Input
        ref={ref}
        type={visible ? 'text' : 'password'}
        disabled={disabled}
        className={cn('pr-11', className)}
        {...rest}
      />
      <button
        type="button"
        onClick={() => setVisible((current) => !current)}
        disabled={disabled}
        aria-label={visible ? 'Ocultar contraseña' : 'Mostrar contraseña'}
        aria-pressed={visible}
        className={cn(
          'absolute inset-y-1 right-1 flex w-9 items-center justify-center rounded-lg text-stone-500 transition-colors',
          'hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-600',
          'disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:text-stone-500',
        )}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-5 w-5" aria-hidden="true">
          <path strokeLinecap="round" strokeLinejoin="round" d={visible ? EYE_SLASH_PATH : EYE_PATH} />
        </svg>
      </button>
    </div>
  )
})
