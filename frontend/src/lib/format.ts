const LOCALE = 'es-CO'

const dateFormatter = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium' })
const dateTimeFormatter = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium', timeStyle: 'short' })
const moneyFormatter = new Intl.NumberFormat(LOCALE, {
  style: 'currency',
  currency: 'COP',
  maximumFractionDigits: 0,
})

/**
 * Parses an ISO date (`YYYY-MM-DD`) as a local date to avoid the UTC shift that
 * `new Date('2025-01-01')` introduces in negative-offset time zones such as Colombia.
 */
function parseIsoDate(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (match) {
    const [, y, m, d] = match
    return new Date(Number(y), Number(m) - 1, Number(d))
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  const date = parseIsoDate(value)
  return date ? dateFormatter.format(date) : value
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return 'Por definir'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateTimeFormatter.format(date)
}

export function formatMoney(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return moneyFormatter.format(value)
}

/**
 * First given name, capitalised: "carlos felipe rodriguez" → "Carlos", "ANA-MARÍA López" → "Ana-María".
 */
export function firstName(fullName: string): string {
  const first = fullName.trim().split(/\s+/)[0] ?? ''
  return first
    .split('-')
    .map((part) => part.charAt(0).toLocaleUpperCase('es') + part.slice(1).toLocaleLowerCase('es'))
    .join('-')
}

/** Returns "Nombre Apellido" initials, e.g. "NA". */
export function initials(fullName: string): string {
  return fullName
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('')
}

/** Today's date as `YYYY-MM-DD` in the local time zone. */
export function todayIso(): string {
  return toIsoDate(new Date())
}

/** ISO timestamp → value for `<input type="datetime-local">` (local time). */
export function toDateTimeLocal(iso: string | null | undefined): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')
  return `${toIsoDate(date)}T${hh}:${mm}`
}

/** `<input type="datetime-local">` value → ISO timestamp (UTC). Empty input → undefined. */
export function fromDateTimeLocal(value: string): string | undefined {
  if (!value) return undefined
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString()
}

/** Kick-off time offered when a match has no time yet (the backend's round-robin default too). */
export const DEFAULT_KICKOFF_TIME = '18:00'

/** A kick-off split into the values of `<input type="date">` (`YYYY-MM-DD`) and `<input type="time">` (`HH:mm`). */
export interface DateTimeParts {
  date: string
  time: string
}

/**
 * ISO timestamp → separate date and time values, in the same local time as `toDateTimeLocal`.
 * Empty or invalid input → both empty.
 */
export function splitDateTime(iso: string | null | undefined): DateTimeParts {
  const local = toDateTimeLocal(iso)
  if (!local) return { date: '', time: '' }
  const [date = '', time = ''] = local.split('T')
  return { date, time }
}

/**
 * Date + time values → ISO timestamp (UTC) through `fromDateTimeLocal`, so the conversion is exactly
 * the one the single "datetime-local" field used. Missing or malformed parts → undefined.
 */
export function joinDateTime({ date, time }: DateTimeParts): string | undefined {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}(:\d{2})?$/.test(time)) return undefined
  return fromDateTimeLocal(`${date}T${time}`)
}

/** True when the timestamp is in the future (or null, which the backend treats as unscheduled). */
export function isFuture(iso: string | null | undefined): boolean {
  if (!iso) return true
  return new Date(iso).getTime() > Date.now()
}

/** Converts a `Date` to the `YYYY-MM-DD` shape the API expects for LocalDate fields. */
export function toIsoDate(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}
