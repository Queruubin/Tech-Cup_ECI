import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import {
  DEFAULT_KICKOFF_TIME,
  firstName,
  fromDateTimeLocal,
  joinDateTime,
  splitDateTime,
  toDateTimeLocal,
  type DateTimeParts,
} from '@/lib/format'
import { DateTimeFields } from './DateTimeFields'

describe('splitDateTime / joinDateTime', () => {
  it('converts exactly like the former datetime-local helpers (same local time zone)', () => {
    const iso = '2026-10-20T23:00:00.000Z'
    const parts = splitDateTime(iso)
    expect(`${parts.date}T${parts.time}`).toBe(toDateTimeLocal(iso))
    expect(joinDateTime(parts)).toBe(fromDateTimeLocal(toDateTimeLocal(iso)))
    expect(joinDateTime(parts)).toBe(iso)
  })

  it('needs both a valid date and a valid time', () => {
    expect(joinDateTime({ date: '2026-10-20', time: '18:00' })).toBe(new Date('2026-10-20T18:00').toISOString())
    expect(joinDateTime({ date: '2026-10-20', time: '' })).toBeUndefined()
    expect(joinDateTime({ date: '', time: '18:00' })).toBeUndefined()
    expect(joinDateTime({ date: '20/10/2026', time: '18:00' })).toBeUndefined()
    expect(splitDateTime(null)).toEqual({ date: '', time: '' })
  })
})

function Harness({ initial }: { initial: DateTimeParts }) {
  const [value, setValue] = useState(initial)
  return (
    <>
      <DateTimeFields value={value} onChange={setValue} required />
      <output data-testid="value">{`${value.date}|${value.time}`}</output>
    </>
  )
}

describe('DateTimeFields', () => {
  it('renders separate "Fecha" and "Hora" inputs', () => {
    render(<Harness initial={{ date: '2026-10-20', time: '20:30' }} />)
    expect(screen.getByLabelText(/^Fecha/)).toHaveAttribute('type', 'date')
    expect(screen.getByLabelText(/^Fecha/)).toHaveValue('2026-10-20')
    expect(screen.getByLabelText(/^Hora/)).toHaveAttribute('type', 'time')
    expect(screen.getByLabelText(/^Hora/)).toHaveValue('20:30')
  })

  it('fills the default kick-off time when a date is picked and the time is empty', async () => {
    render(<Harness initial={{ date: '', time: '' }} />)
    await userEvent.setup().type(screen.getByLabelText(/^Fecha/), '2026-10-20')
    expect(screen.getByTestId('value')).toHaveTextContent(`2026-10-20|${DEFAULT_KICKOFF_TIME}`)
  })

  it('keeps a time the user already chose when the date changes', async () => {
    render(<Harness initial={{ date: '2026-10-20', time: '09:45' }} />)
    const user = userEvent.setup()
    await user.clear(screen.getByLabelText(/^Fecha/))
    await user.type(screen.getByLabelText(/^Fecha/), '2026-11-02')
    expect(screen.getByTestId('value')).toHaveTextContent('2026-11-02|09:45')
  })
})

describe('firstName', () => {
  it('keeps only the first given name, properly capitalised', () => {
    expect(firstName('carlos felipe rodriguez tellez')).toBe('Carlos')
    expect(firstName('  ÁLVARO   Gómez ')).toBe('Álvaro')
    expect(firstName('ana-maría lópez')).toBe('Ana-María')
    expect(firstName('')).toBe('')
  })
})
