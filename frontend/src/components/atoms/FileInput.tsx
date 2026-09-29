import { useId, useRef, useState, type ChangeEvent } from 'react'
import { cn } from '@/lib/cn'
import { formatSize, validateFile } from '@/lib/uploads'

export interface FileInputProps {
  id?: string
  name?: string
  /** HTML accept list; also enforced client-side before the file reaches the caller. */
  accept?: string
  /** Files larger than this are rejected with an inline message before upload. */
  maxBytes?: number
  disabled?: boolean
  invalid?: boolean
  value: File | null
  onChange: (file: File | null) => void
  /** Helper shown when no file is selected. */
  hint?: string
  className?: string
}

export function FileInput({
  id,
  name,
  accept,
  maxBytes,
  disabled,
  invalid,
  value,
  onChange,
  hint = 'Seleccione un archivo',
  className,
}: FileInputProps) {
  const generatedId = useId()
  const inputId = id ?? generatedId
  const errorId = `${inputId}-error`
  const inputRef = useRef<HTMLInputElement>(null)
  const [localError, setLocalError] = useState<string | null>(null)

  const resetInput = () => {
    if (inputRef.current) inputRef.current.value = ''
  }

  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0] ?? null
    const error = file ? validateFile(file, { accept, maxBytes }) : undefined
    if (error) {
      setLocalError(error)
      resetInput()
      onChange(null)
      return
    }
    setLocalError(null)
    onChange(file)
  }

  const clear = () => {
    resetInput()
    setLocalError(null)
    onChange(null)
  }

  const showInvalid = invalid || !!localError

  return (
    <div className={className}>
      <div
        className={cn(
          'flex items-center gap-3 rounded-xl border border-dashed bg-white px-3.5 py-3 text-sm',
          showInvalid ? 'border-brand-400' : 'border-stone-300',
          disabled && 'opacity-60',
        )}
      >
        <label
          htmlFor={inputId}
          className={cn(
            'shrink-0 cursor-pointer rounded-lg bg-stone-100 px-3 py-1.5 font-medium text-stone-800 hover:bg-stone-200',
            disabled && 'pointer-events-none',
          )}
        >
          Elegir archivo
        </label>
        <input
          ref={inputRef}
          id={inputId}
          name={name}
          type="file"
          accept={accept}
          disabled={disabled}
          aria-invalid={showInvalid || undefined}
          aria-describedby={localError ? errorId : undefined}
          onChange={handleChange}
          className="sr-only"
        />
        <span className="min-w-0 flex-1 truncate text-stone-600">
          {value ? (
            <>
              <span className="font-medium text-ink">{value.name}</span>{' '}
              <span className="text-stone-500">({formatSize(value.size)})</span>
            </>
          ) : (
            hint
          )}
        </span>
        {value && !disabled && (
          <button type="button" onClick={clear} className="shrink-0 text-xs text-stone-500 hover:text-brand-600">
            Quitar
          </button>
        )}
      </div>
      {localError && (
        <p id={errorId} role="alert" className="mt-1 text-xs font-medium text-brand-600">
          {localError}
        </p>
      )}
    </div>
  )
}
