import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  GroupResponse,
  GroupStandings,
  KnockoutSuggestion,
  RegistrationResponse,
  StandingRow,
  TournamentResponse,
  UserResponse,
} from '@/types/api'
import { CreateMatchForm } from './components/CreateMatchForm'
import { GroupsManager } from './components/GroupsManager'
import { KnockoutBuilderDialog } from './components/KnockoutBuilderDialog'
import { OrganizerPanel } from './components/OrganizerPanel'
import { StandingsView } from './components/StandingsTab'
import {
  knockoutStarted,
  latestPhase,
  pairingTeamOptions,
  unassignedTeams,
  validatePairings,
} from './groupStage'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function registration(teamId: number, teamName: string, status: RegistrationResponse['status'] = 'APPROVED'): RegistrationResponse {
  return {
    id: teamId * 10,
    tournamentId: 1,
    teamId,
    teamName,
    receiptFileId: 'receipt',
    status,
    reviewNote: null,
    createdAt: '2026-08-01T10:00:00Z',
    reviewedAt: null,
  } as RegistrationResponse
}

const GROUP_A: GroupResponse = {
  id: 5,
  name: 'Grupo A',
  position: 0,
  teams: [
    { id: 1, name: 'Tigres', colors: 'rojo', hasMatches: true },
    { id: 2, name: 'Leones', colors: 'azul', hasMatches: false },
  ],
  matchCount: 1,
}
const GROUP_B: GroupResponse = {
  id: 6,
  name: 'Grupo B',
  position: 1,
  teams: [
    { id: 3, name: 'Pumas', colors: 'verde', hasMatches: false },
    { id: 4, name: 'Osos', colors: 'negro', hasMatches: false },
  ],
  matchCount: 0,
}
const EMPTY_GROUP: GroupResponse = { id: 7, name: 'Grupo C', position: 2, teams: [], matchCount: 0 }

const TEAMS = [
  { id: 1, name: 'Tigres' },
  { id: 2, name: 'Leones' },
  { id: 3, name: 'Pumas' },
  { id: 4, name: 'Osos' },
]

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse([]))
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('group stage helpers', () => {
  it('lists the approved teams that have no group', () => {
    const registrations = [registration(1, 'Tigres'), registration(9, 'Zorros'), registration(8, 'Aguilas', 'REJECTED')]
    expect(unassignedTeams(registrations, [GROUP_A])).toEqual([{ id: 9, name: 'Zorros' }])
  })

  it('knows when the knockout started and which phase is the latest', () => {
    expect(knockoutStarted([{ phase: 'GROUP' }])).toBe(false)
    expect(knockoutStarted([{ phase: 'GROUP' }, { phase: 'SEMIFINAL' }])).toBe(true)
    expect(latestPhase([{ phase: 'GROUP' }, { phase: 'SEMIFINAL' }])).toBe('SEMIFINAL')
    expect(latestPhase([])).toBeNull()
  })

  it('refuses incomplete pairings, a team against itself and a team used twice', () => {
    const result = validatePairings([
      { homeTeamId: '1', awayTeamId: '' },
      { homeTeamId: '2', awayTeamId: '2' },
      { homeTeamId: '3', awayTeamId: '4' },
      { homeTeamId: '4', awayTeamId: '5' },
    ])
    expect(result.valid).toBe(false)
    expect(result.errors).toEqual([
      'Elija los dos equipos del cruce.',
      'Un equipo no puede jugar contra sí mismo.',
      'Un equipo aparece en más de un cruce.',
      'Un equipo aparece en más de un cruce.',
    ])
    expect(validatePairings([{ homeTeamId: '1', awayTeamId: '2' }]).valid).toBe(true)
  })

  it('never offers a team that another slot already uses', () => {
    const pairings = [
      { homeTeamId: '1', awayTeamId: '2' },
      { homeTeamId: '3', awayTeamId: '' },
    ]
    expect(pairingTeamOptions(TEAMS, pairings, 1, 'awayTeamId').map((option) => option.value)).toEqual(['4'])
    expect(pairingTeamOptions(TEAMS, pairings, 0, 'homeTeamId').map((option) => option.value)).toEqual(['1', '4'])
  })
})

describe('GroupsManager', () => {
  function renderManager(props: Partial<Parameters<typeof GroupsManager>[0]> = {}) {
    const onChanged = vi.fn()
    render(
      <GroupsManager
        tournamentId={1}
        groups={[GROUP_A, GROUP_B, EMPTY_GROUP]}
        unassigned={[{ id: 9, name: 'Zorros' }]}
        groupsEditable
        matchesEditable
        onChanged={onChanged}
        {...props}
      />,
    )
    return onChanged
  }

  it('shows each group, its teams and the teams without a group', () => {
    renderManager()
    const groupA = screen.getByRole('region', { name: 'Grupo A' })
    expect(within(groupA).getByText('Tigres')).toBeInTheDocument()
    expect(within(groupA).getByText(/2 equipos · 1 partido/)).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Equipos sin grupo' })).getByText('Zorros')).toBeInTheDocument()
  })

  it('a team with matches in its group cannot be removed; only the empty group can be deleted', () => {
    renderManager()
    expect(screen.getByRole('button', { name: 'Quitar Tigres del Grupo A' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Quitar Leones del Grupo A' })).toBeEnabled()
    expect(within(screen.getByRole('region', { name: 'Grupo A' })).queryByRole('button', { name: 'Eliminar grupo' })).toBeNull()
    expect(within(screen.getByRole('region', { name: 'Grupo C' })).getByRole('button', { name: 'Eliminar grupo' })).toBeInTheDocument()
    // A group needs two teams for its round robin.
    expect(within(screen.getByRole('region', { name: 'Grupo C' })).getByRole('button', { name: 'Generar todos contra todos' })).toBeDisabled()
  })

  it('adds an unassigned team to a group', async () => {
    const fetchMock = vi.mocked(globalThis.fetch)
    fetchMock.mockImplementation(async () => jsonResponse(GROUP_B))
    const user = userEvent.setup()
    const onChanged = renderManager()

    await user.selectOptions(screen.getByRole('combobox', { name: 'Equipo para el Grupo B' }), '9')
    await user.click(within(screen.getByRole('region', { name: 'Grupo B' })).getByRole('button', { name: 'Agregar' }))

    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(false))
    const [url, init] = fetchMock.mock.calls[0] as [RequestInfo, RequestInit]
    expect(String(url)).toContain('/tournaments/1/groups/6/teams/9')
    expect(init.method).toBe('PUT')
  })

  it('asks for confirmation before generating all against all in a group', async () => {
    const fetchMock = vi.mocked(globalThis.fetch)
    fetchMock.mockImplementation(async () => jsonResponse([{ id: 1 }, { id: 2 }], 201))
    const user = userEvent.setup()
    const onChanged = renderManager()

    await user.click(within(screen.getByRole('region', { name: 'Grupo B' })).getByRole('button', { name: 'Generar todos contra todos' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/solo los partidos que le faltan al Grupo B/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Generar' }))

    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(true))
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/tournaments/1/groups/6/round-robin')
  })

  it('is read-only when groups cannot change (suspended, finished or knockout started)', () => {
    renderManager({ groupsEditable: false, matchesEditable: false })
    expect(screen.queryByRole('button', { name: 'Crear grupo' })).toBeNull()
    expect(screen.queryByRole('button', { name: /Quitar/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Generar todos contra todos' })).toBeNull()
  })

  it('explains what to do when there are no groups', () => {
    renderManager({ groups: [] })
    expect(screen.getByText('Cree grupos y asigne equipos para programar partidos.')).toBeInTheDocument()
  })
})

describe('CreateMatchForm', () => {
  const referee = { id: 50, fullName: 'Árbitro Uno', status: 'ACTIVE' } as UserResponse

  it('offers only the teams of the chosen group and submits the match', async () => {
    const onSubmit = vi.fn(async () => undefined)
    const user = userEvent.setup()
    render(
      <CreateMatchForm
        groups={[GROUP_A, GROUP_B]}
        venues={[{ id: 3, name: 'Cancha 1', description: '', imageFileId: null }]}
        referees={[referee]}
        loading={false}
        error={null}
        fieldErrors={{}}
        onSubmit={onSubmit}
      />,
    )
    const home = screen.getByRole('combobox', { name: /Equipo local/ })
    expect(home).toBeDisabled()

    await user.selectOptions(screen.getByRole('combobox', { name: /Grupo/ }), '6')
    const homeOptions = within(home).getAllByRole('option').map((option) => option.textContent)
    expect(homeOptions).toEqual(['Elija un equipo', 'Pumas', 'Osos'])

    await user.selectOptions(home, '3')
    const away = screen.getByRole('combobox', { name: /Equipo visitante/ })
    expect(within(away).queryByRole('option', { name: 'Pumas' })).toBeNull()
    await user.selectOptions(away, '4')
    // The time starts at the default kick-off; the date is typed in its own field.
    expect(screen.getByLabelText(/^Hora/)).toHaveValue('18:00')
    await user.type(screen.getByLabelText(/^Fecha/), '2026-10-20')
    await user.clear(screen.getByLabelText(/^Hora/))
    await user.type(screen.getByLabelText(/^Hora/), '20:30')
    await user.selectOptions(screen.getByRole('combobox', { name: /Cancha/ }), '3')
    await user.click(screen.getByRole('button', { name: 'Crear partido' }))

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        groupId: 6,
        homeTeamId: 3,
        awayTeamId: 4,
        venueId: 3,
        // Same local-time conversion as the former single "datetime-local" field.
        scheduledAt: new Date('2026-10-20T20:30').toISOString(),
      }),
    )
  })

  it('asks for the missing fields instead of submitting', async () => {
    const onSubmit = vi.fn(async () => undefined)
    const user = userEvent.setup()
    render(
      <CreateMatchForm groups={[GROUP_A]} venues={[]} referees={[]} loading={false} error={null} fieldErrors={{}} onSubmit={onSubmit} />,
    )
    await user.click(screen.getByRole('button', { name: 'Crear partido' }))
    expect(screen.getByText('Elija el equipo local.')).toBeInTheDocument()
    expect(screen.getByText('Indique la fecha.')).toBeInTheDocument()
    expect(screen.queryByText('Indique la hora.')).not.toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('requires the time too when the organizer clears it', async () => {
    const onSubmit = vi.fn(async () => undefined)
    const user = userEvent.setup()
    render(
      <CreateMatchForm groups={[GROUP_A]} venues={[]} referees={[]} loading={false} error={null} fieldErrors={{}} onSubmit={onSubmit} />,
    )
    await user.type(screen.getByLabelText(/^Fecha/), '2026-10-20')
    await user.clear(screen.getByLabelText(/^Hora/))
    await user.click(screen.getByRole('button', { name: 'Crear partido' }))
    expect(screen.getByText('Indique la hora.')).toBeInTheDocument()
    expect(screen.queryByText('Indique la fecha.')).not.toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })
})

describe('KnockoutBuilderDialog', () => {
  const suggestion: KnockoutSuggestion = {
    phase: 'SEMIFINAL',
    size: 4,
    source: 'GROUPS',
    pairings: [
      {
        home: { teamId: 1, teamName: 'Tigres', label: '1° Grupo A', groupId: 5 },
        away: { teamId: 4, teamName: 'Osos', label: '2° Grupo B', groupId: 6 },
      },
      {
        home: { teamId: 3, teamName: 'Pumas', label: '1° Grupo B', groupId: 6 },
        away: { teamId: 2, teamName: 'Leones', label: '2° Grupo A', groupId: 5 },
      },
    ],
  }

  it('loads the suggestion, lets the organizer edit a pairing and creates the knockout in that order', async () => {
    const fetchMock = vi.mocked(globalThis.fetch)
    fetchMock.mockImplementation(async (input) =>
      String(input).includes('/knockout/suggestion') ? jsonResponse(suggestion) : jsonResponse([{ id: 1 }, { id: 2 }], 201),
    )
    const onCreated = vi.fn()
    const user = userEvent.setup()
    render(<KnockoutBuilderDialog open tournamentId={1} teams={TEAMS} onClose={() => undefined} onCreated={onCreated} />)

    await user.click(screen.getByRole('button', { name: 'Cargar sugerencia' }))
    const firstAway = await screen.findByRole('combobox', { name: 'Cruce 1: visitante' })
    await waitFor(() => expect(firstAway).toHaveValue('4'))
    expect(screen.getByText('1° Grupo A')).toBeInTheDocument()
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('size=4')

    // Swap the two away teams: free Osos first, then use it in the second pairing.
    const secondAway = screen.getByRole('combobox', { name: 'Cruce 2: visitante' })
    expect(within(secondAway).queryByRole('option', { name: 'Osos' })).toBeNull()
    await user.selectOptions(firstAway, '')
    await user.selectOptions(secondAway, '4')
    await user.selectOptions(firstAway, '2')
    await user.click(screen.getByRole('button', { name: 'Crear eliminatoria' }))

    await waitFor(() => expect(onCreated).toHaveBeenCalled())
    const createCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/tournaments/1/knockout'))
    expect(createCall).toBeDefined()
    const init = (createCall as [RequestInfo, RequestInit])[1]
    expect(JSON.parse(String(init.body))).toEqual({
      pairings: [
        { homeTeamId: 1, awayTeamId: 2 },
        { homeTeamId: 3, awayTeamId: 4 },
      ],
    })
  })

  it('does not submit incomplete pairings', async () => {
    const fetchMock = vi.mocked(globalThis.fetch)
    const user = userEvent.setup()
    render(<KnockoutBuilderDialog open tournamentId={1} teams={TEAMS} onClose={() => undefined} onCreated={() => undefined} />)

    await user.click(screen.getByRole('button', { name: 'Crear eliminatoria' }))
    expect(screen.getAllByText('Elija los dos equipos del cruce.')).toHaveLength(2)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('StandingsView', () => {
  const row = (position: number, teamId: number, teamName: string): StandingRow => ({
    position,
    teamId,
    teamName,
    played: 1,
    won: 0,
    drawn: 0,
    lost: 0,
    goalsFor: 0,
    goalsAgainst: 0,
    goalDifference: 0,
    points: 3 - position,
  })
  const general = [row(1, 1, 'Tigres'), row(2, 3, 'Pumas')]
  const groups: GroupStandings[] = [
    { groupId: 5, name: 'Grupo A', rows: [row(1, 1, 'Tigres')] },
    { groupId: 6, name: 'Grupo B', rows: [row(1, 3, 'Pumas')] },
  ]

  it('shows only the general table when there are no groups', () => {
    render(
      <MemoryRouter>
        <StandingsView general={general} groups={[]} highlightTeamId={null} />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: 'Por grupos' })).toBeNull()
    expect(screen.getByText('Pumas')).toBeInTheDocument()
  })

  it('switches between the general table and one table per group', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <StandingsView general={general} groups={groups} highlightTeamId={null} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('button', { name: 'General' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('region', { name: 'Grupo A' })).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Por grupos' }))
    expect(within(screen.getByRole('region', { name: 'Grupo A' })).getByText('Tigres')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Grupo B' })).getByText('Pumas')).toBeInTheDocument()
  })
})

describe('OrganizerPanel without the global fixture generation', () => {
  function tournament(status: TournamentResponse['status']): TournamentResponse {
    return {
      id: 1,
      name: 'TechCup 2026-2',
      startDate: '2026-09-01',
      endDate: '2026-11-30',
      registrationDeadline: '2026-08-25',
      maxTeams: 8,
      fee: 50000,
      status,
      rulebookFileId: 'rulebook',
      venues: [],
      approvedTeams: 4,
      suspendedFromStatus: null,
    }
  }

  it('offers groups, match creation and the knockout builder instead of "Generar fixture"', async () => {
    render(
      <MemoryRouter>
        <OrganizerPanel tournament={tournament('IN_PROGRESS')} onUpdated={() => undefined} />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: 'Generar fixture' })).toBeNull()
    expect(await screen.findByRole('button', { name: 'Crear grupo' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Armar eliminatoria' })).toBeInTheDocument()
    expect(screen.getAllByText('Cree grupos y asigne equipos para programar partidos.').length).toBeGreaterThan(0)
    // The next knockout round is automatic: no "Avanzar fase" while there is no knockout.
    expect(screen.queryByRole('button', { name: 'Avanzar fase' })).toBeNull()
  })

  it('a suspended tournament shows its groups read-only', async () => {
    render(
      <MemoryRouter>
        <OrganizerPanel tournament={{ ...tournament('SUSPENDED'), suspendedFromStatus: 'IN_PROGRESS' }} onUpdated={() => undefined} />
      </MemoryRouter>,
    )
    expect(await screen.findByText('Sin grupos')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Crear grupo' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Armar eliminatoria' })).toBeNull()
  })
})
