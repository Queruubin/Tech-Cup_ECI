import { Input } from '@/components/atoms/Input'
import { cn } from '@/lib/cn'
import { DEFAULT_KICKOFF_TIME, type DateTimeParts } from '@/lib/format'
import { FormField } from './FormField'

export interface DateTimeFieldsProps {
  value: DateTimeParts
  onChange: (value: DateTimeParts) => void
  dateLabel?: string
  timeLabel?: string
  dateError?: string | null
  timeError?: string | null
  dateHint?: string
  timeHint?: string
  required?: boolean
  className?: string
}

/**
 * Separate "Fecha" and "Hora" inputs for a kick-off. Choosing a date while the time is empty fills in
 * the default kick-off time (18:00), which the user can still change. Convert with `joinDateTime` /
 * `splitDateTime` (`@/lib/format`), the same local-time conversion as the former single field.
 */
export function DateTimeFields({
  value,
  onChange,
  dateLabel = 'Fecha',
  timeLabel = 'Hora',
  dateError,
  timeError,
  dateHint,
  timeHint,
  required,
  className,
}: DateTimeFieldsProps) {
  const changeDate = (date: string) =>
    onChange({ date, time: value.time || (date ? DEFAULT_KICKOFF_TIME : '') })

  return (
    <div className={cn('grid grid-cols-[minmax(0,3fr)_minmax(0,2fr)] gap-4', className)}>
      <FormField label={dateLabel} required={required} error={dateError} hint={dateHint}>
        <Input type="date" value={value.date} onChange={(event) => changeDate(event.target.value)} />
      </FormField>
      <FormField label={timeLabel} required={required} error={timeError} hint={timeHint}>
        <Input type="time" value={value.time} onChange={(event) => onChange({ ...value, time: event.target.value })} />
      </FormField>
    </div>
  )
}
