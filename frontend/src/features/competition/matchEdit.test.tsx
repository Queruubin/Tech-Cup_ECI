import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { toDateTimeLocal } from '@/lib/format'
import type { MatchResponse, RegistrationResponse, UserResponse } from '@/types/api'
import { MatchEditForm } from './components/MatchEditForm'
import {
  KNOCKOUT_REDRAW_WARNING,
  NO_CHANGES_MESSAGE,
  PAST_DATE_HINT,
  SAME_TEAMS_ERROR,
  TEAMS_LOCKED_HINT,
  approvedTeamOptions,
  hasMatchChanges,
  isPastDateTime,
  knockoutDrawnFromGroups,
  matchEditValuesFromMatch,
  refereeOptions,
  resultSavedMessage,
  teamsSelectState,
  toUpdateMatchRequest,
  validateMatchEdit,
} from './matchEdit'

const HOME = { id: 10, name: 'Leones FC', colors: 'Rojo' }
const AWAY = { id: 20, name: 'Aguilas FC', colors: 'Azul' }

function makeMatch(overrides: Partial<MatchResponse> = {}): MatchResponse {
  return {
    id: 7,
    tournamentId: 1,
    phase: 'GROUP',
    roundNumber: 1,
    homeTeam: HOME,
    awayTeam: AWAY,
    venue: { id: 1, name: 'Cancha norte' },
    referee: { id: 99, fullName: 'Ana Ruiz' },
    scheduledAt: '2099-03-10T23:00:00Z',
    status: 'SCHEDULED',
    homeScore: null,
    awayScore: null,
    homePenalties: null,
    awayPenalties: null,
    cancelReason: null,
    walkoverWinnerTeamId: null,
    resultEditable: true,
    teamsEditable: true,
    reopenable: false,
    events: [],
    ...overrides,
  }
}

function registration(teamId: number, teamName: string, status: RegistrationResponse['status']): RegistrationResponse {
  return {
    id: teamId,
    tournamentId: 1,
    teamId,
    teamName,
    receiptFileId: null,
    status,
    reviewNote: null,
    createdAt: '2026-01-01T00:00:00Z',
    reviewedAt: null,
  }
}

function referee(id: number, fullName: string, status: UserResponse['status']): UserResponse {
  return {
    id,
    fullName,
    email: null,
    schoolRelation: null,
    academicProgram: null,
    semester: null,
    status,
    birthDate: null,
    documentType: null,
    documentNumber: null,
    roles: ['REFEREE'],
    hasProfile: false,
  }
}

const TEAMS = [
  { value: '20', label: 'Aguilas FC' },
  { value: '10', label: 'Leones FC' },
  { value: '30', label: 'Toros FC' },
]

describe('teamsSelectState', () => {
  it('enables the selects while the match is not played', () => {
    expect(teamsSelectState({ teamsEditable: true })).toEqual({ disabled: false, hint: null })
  })

  it('locks them with the reopen hint otherwise', () => {
    expect(teamsSelectState({ teamsEditable: false })).toEqual({ disabled: true, hint: TEAMS_LOCKED_HINT })
  })
})

describe('approvedTeamOptions', () => {
  it('keeps only APPROVED registrations, sorted by name, and always includes the current teams', () => {
    const options = approvedTeamOptions(
      [registration(30, 'Toros FC', 'APPROVED'), registration(40, 'Pumas FC', 'REJECTED'), registration(20, 'Aguilas FC', 'APPROVED')],
      [HOME, AWAY],
    )
    expect(options).toEqual(TEAMS)
  })
})

describe('isPastDateTime', () => {
  const now = new Date('2026-06-15T12:00:00Z').getTime()

  it('detects past and future values', () => {
    expect(isPastDateTime(toDateTimeLocal('2026-06-01T12:00:00Z'), now)).toBe(true)
    expect(isPastDateTime(toDateTimeLocal('2026-07-01T12:00:00Z'), now)).toBe(false)
  })

  it('treats empty or invalid values as not past', () => {
    expect(isPastDateTime('', now)).toBe(false)
    expect(isPastDateTime('not-a-date', now)).toBe(false)
  })
})

describe('validateMatchEdit / toUpdateMatchRequest', () => {
  it('rejects the same team on both sides but accepts past dates', () => {
    const values = { ...matchEditValuesFromMatch(makeMatch()), scheduledAt: '2020-01-01T10:00', awayTeamId: '10' }
    expect(validateMatchEdit(values)).toEqual({ awayTeamId: SAME_TEAMS_ERROR })
    expect(validateMatchEdit({ ...values, awayTeamId: '30' })).toEqual({})
  })

  it('sends only the fields that changed (never the unchanged referee, venue or date)', () => {
    const match = makeMatch()
    const payload = toUpdateMatchRequest(match, { ...matchEditValuesFromMatch(match), awayTeamId: '30' })
    expect(payload).toEqual({ awayTeamId: 30 })
  })

  it('returns an empty body when nothing changed', () => {
    const match = makeMatch()
    const payload = toUpdateMatchRequest(match, matchEditValuesFromMatch(match))
    expect(payload).toEqual({})
    expect(hasMatchChanges(payload)).toBe(false)
  })

  it('sends a new referee, venue or date only when they differ from the match', () => {
    const match = makeMatch()
    const payload = toUpdateMatchRequest(match, {
      ...matchEditValuesFromMatch(match),
      refereeId: '100',
      venueId: '2',
      scheduledAt: '2099-04-01T18:00',
    })
    expect(payload).toEqual({ refereeId: 100, venueId: 2, scheduledAt: new Date('2099-04-01T18:00').toISOString() })
    expect(hasMatchChanges(payload)).toBe(true)
  })

  it('keeps an inactive assigned referee out of the body when only the date changes', () => {
    const match = makeMatch({ referee: { id: 99, fullName: 'Ana Ruiz' } })
    const payload = toUpdateMatchRequest(match, { ...matchEditValuesFromMatch(match), scheduledAt: '2099-04-01T18:00' })
    expect(payload).not.toHaveProperty('refereeId')
  })

  it('never sends teams when the match no longer allows it', () => {
    const match = makeMatch({ status: 'PLAYED', teamsEditable: false })
    const payload = toUpdateMatchRequest(match, { ...matchEditValuesFromMatch(match), homeTeamId: '30' })
    expect(payload).not.toHaveProperty('homeTeamId')
    expect(payload).not.toHaveProperty('awayTeamId')
  })
})

describe('refereeOptions', () => {
  it('lists only ACTIVE referees, sorted by name', () => {
    const options = refereeOptions([referee(2, 'Beto Gil', 'ACTIVE'), referee(3, 'Carla Paz', 'INACTIVE'), referee(1, 'Ana Ruiz', 'ACTIVE')])
    expect(options).toEqual([
      { value: '1', label: 'Ana Ruiz' },
      { value: '2', label: 'Beto Gil' },
    ])
  })

  it('always keeps the assigned referee, flagged when inactive', () => {
    const options = refereeOptions([referee(1, 'Ana Ruiz', 'ACTIVE'), referee(3, 'Carla Paz', 'INACTIVE')], { id: 3, fullName: 'Carla Paz' })
    expect(options).toContainEqual({ value: '3', label: 'Carla Paz (inactivo)' })
  })

  it('keeps the assigned referee even if the list does not include it', () => {
    expect(refereeOptions([], { id: 9, fullName: 'Dora Lis' })).toEqual([{ value: '9', label: 'Dora Lis' }])
  })
})

describe('knockoutDrawnFromGroups', () => {
  it('is true for a group match once any knockout match exists', () => {
    expect(knockoutDrawnFromGroups({ phase: 'GROUP' }, [{ phase: 'GROUP' }, { phase: 'SEMIFINAL' }])).toBe(true)
  })

  it('is false while only group matches exist, or before the matches load', () => {
    expect(knockoutDrawnFromGroups({ phase: 'GROUP' }, [{ phase: 'GROUP' }])).toBe(false)
    expect(knockoutDrawnFromGroups({ phase: 'GROUP' }, null)).toBe(false)
  })

  it('is false for knockout matches', () => {
    expect(knockoutDrawnFromGroups({ phase: 'SEMIFINAL' }, [{ phase: 'SEMIFINAL' }, { phase: 'FINAL' }])).toBe(false)
  })
})

describe('resultSavedMessage', () => {
  it('asks to review the bracket only after correcting a knockout result', () => {
    expect(resultSavedMessage('SEMIFINAL', true)).toMatch(/Revise las llaves/)
    expect(resultSavedMessage('GROUP', true)).toBe('Resultado corregido.')
    expect(resultSavedMessage('FINAL', false)).toBe('Resultado registrado.')
  })

  it('warns about re-drawing the knockout when a group result changes after it was generated', () => {
    expect(resultSavedMessage('GROUP', true, true)).toBe(KNOCKOUT_REDRAW_WARNING)
    expect(resultSavedMessage('GROUP', false, true)).toBe(KNOCKOUT_REDRAW_WARNING)
    expect(resultSavedMessage('GROUP', true, false)).toBe('Resultado corregido.')
  })
})

describe('MatchEditForm', () => {
  const baseProps = {
    venues: [{ id: 1, name: 'Cancha norte', description: '', imageFileId: null }],
    referees: [],
    teams: TEAMS,
    loading: false,
    error: null,
    fieldErrors: {},
  }

  it('disables the team selects with a hint when teams are not editable', () => {
    render(<MatchEditForm {...baseProps} match={makeMatch({ status: 'PLAYED', teamsEditable: false })} onSubmit={vi.fn()} />)
    expect(screen.getByLabelText('Equipo local')).toBeDisabled()
    expect(screen.getByLabelText('Equipo visitante')).toBeDisabled()
    expect(screen.getAllByText(TEAMS_LOCKED_HINT)).toHaveLength(2)
  })

  it('shows the past-date hint and submits a past date with the new team', async () => {
    const onSubmit = vi.fn()
    render(<MatchEditForm {...baseProps} match={makeMatch()} onSubmit={onSubmit} />)
    expect(screen.queryByText(PAST_DATE_HINT)).not.toBeInTheDocument()

    const user = userEvent.setup()
    const date = screen.getByLabelText('Fecha y hora')
    await user.clear(date)
    await user.type(date, '2020-05-01T15:30')
    expect(screen.getByText(PAST_DATE_HINT)).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Equipo visitante'), '30')
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ scheduledAt: new Date('2020-05-01T15:30').toISOString(), awayTeamId: 30 }),
    )
  })

  it('does not submit when nothing changed and says so', async () => {
    const onSubmit = vi.fn()
    render(<MatchEditForm {...baseProps} match={makeMatch()} onSubmit={onSubmit} />)
    await userEvent.setup().click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(screen.getByText(NO_CHANGES_MESSAGE)).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('offers only active referees plus the assigned inactive one, and does not resend it', async () => {
    const onSubmit = vi.fn()
    const referees = [referee(99, 'Ana Ruiz', 'INACTIVE'), referee(100, 'Beto Gil', 'ACTIVE'), referee(101, 'Carla Paz', 'INACTIVE')]
    render(<MatchEditForm {...baseProps} referees={referees} match={makeMatch()} onSubmit={onSubmit} />)
    const select = screen.getByLabelText('Árbitro') as HTMLSelectElement
    expect(select.value).toBe('99')
    expect(screen.getByRole('option', { name: 'Ana Ruiz (inactivo)' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Beto Gil' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /Carla Paz/ })).not.toBeInTheDocument()

    const user = userEvent.setup()
    await user.selectOptions(screen.getByLabelText('Equipo visitante'), '30')
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(onSubmit).toHaveBeenCalledWith({ awayTeamId: 30 })
  })

  it('blocks submitting the same team twice', async () => {
    const onSubmit = vi.fn()
    render(<MatchEditForm {...baseProps} match={makeMatch()} onSubmit={onSubmit} />)
    const user = userEvent.setup()
    await user.selectOptions(screen.getByLabelText('Equipo visitante'), '10')
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(screen.getByText(SAME_TEAMS_ERROR)).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })
})
