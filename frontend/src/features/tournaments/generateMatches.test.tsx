import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { GroupResponse, MatchResponse, RegistrationResponse, TournamentResponse } from '@/types/api'
import { GroupStageSection } from './components/GroupStageSection'
import { generateAllState, isGroupMatchDeletable, pairingsNeeded } from './groupStage'

/** "Generar partidos" (every group at once) and deleting group matches from "Gestión". */

const FIND = { timeout: 4000 }

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

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
    approvedTeams: 3,
    suspendedFromStatus: null,
  }
}

function registration(teamId: number, teamName: string): RegistrationResponse {
  return {
    id: teamId * 10,
    tournamentId: 1,
    teamId,
    teamName,
    receiptFileId: null,
    status: 'APPROVED',
    reviewNote: null,
    createdAt: '2026-08-01T10:00:00Z',
    reviewedAt: null,
  } as RegistrationResponse
}

function group(teams: { id: number; name: string }[], matchCount: number): GroupResponse {
  return {
    id: 5,
    name: 'Grupo A',
    position: 0,
    teams: teams.map((team) => ({ ...team, colors: 'rojo', hasMatches: matchCount > 0 })),
    matchCount,
  }
}

const TIGRES = { id: 1, name: 'Tigres' }
const LEONES = { id: 2, name: 'Leones' }
const PUMAS = { id: 3, name: 'Pumas' }

function groupMatch(id: number, status: MatchResponse['status']): MatchResponse {
  return {
    id,
    tournamentId: 1,
    phase: 'GROUP',
    roundNumber: 1,
    group: { id: 5, name: 'Grupo A' },
    homeTeam: { id: 1, name: 'Tigres', colors: 'rojo' },
    awayTeam: { id: 2, name: 'Leones', colors: 'azul' },
    venue: null,
    referee: null,
    scheduledAt: '2026-10-10T23:00:00Z',
    status,
    homeScore: status === 'PLAYED' ? 1 : null,
    awayScore: status === 'PLAYED' ? 0 : null,
    homePenalties: null,
    awayPenalties: null,
    cancelReason: null,
    walkoverWinnerTeamId: null,
    resultEditable: true,
    teamsEditable: status !== 'PLAYED',
    reopenable: status !== 'SCHEDULED',
    nextMatchId: null,
    nextSlot: null,
    events: [],
  }
}

function pathOf(input: RequestInfo | URL): string {
  return new URL(String(input), 'http://localhost').pathname.replace(/^\/api/, '')
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('generateAllState', () => {
  const twoTeams = [group([TIGRES, LEONES], 0)]

  it('is enabled in progress with every approved team in a group and pairings missing', () => {
    expect(generateAllState('IN_PROGRESS', [], twoTeams, [])).toEqual({ enabled: true, reason: null })
  })

  it('explains every case the server refuses', () => {
    expect(generateAllState('SUSPENDED', [], twoTeams, []).reason).toBe('El torneo está suspendido.')
    expect(generateAllState('ACTIVE', [], twoTeams, []).reason).toBe('Disponible cuando el torneo esté en progreso.')
    expect(generateAllState('IN_PROGRESS', [{ phase: 'SEMIFINAL' }], twoTeams, []).reason).toBe('La eliminatoria ya comenzó.')
    expect(generateAllState('IN_PROGRESS', [], [group([TIGRES], 0)], []).reason).toBe(
      'Se necesita al menos un grupo con 2 equipos.',
    )
    expect(generateAllState('IN_PROGRESS', [], twoTeams, [PUMAS, { id: 4, name: 'Cóndores' }]).reason).toBe(
      'Asigne todos los equipos aprobados a un grupo. Faltan: Pumas, Cóndores.',
    )
    expect(generateAllState('IN_PROGRESS', [], [group([TIGRES, LEONES, PUMAS], pairingsNeeded(3))], []).reason).toBe(
      'Todos los grupos ya tienen un partido para cada pareja de equipos.',
    )
  })

  it('a group match without result is deletable only while the group stage is open', () => {
    expect(isGroupMatchDeletable({ phase: 'GROUP', status: 'SCHEDULED' }, 'IN_PROGRESS', false)).toBe(true)
    expect(isGroupMatchDeletable({ phase: 'GROUP', status: 'CANCELLED' }, 'IN_PROGRESS', false)).toBe(true)
    expect(isGroupMatchDeletable({ phase: 'GROUP', status: 'PLAYED' }, 'IN_PROGRESS', false)).toBe(false)
    expect(isGroupMatchDeletable({ phase: 'GROUP', status: 'SCHEDULED' }, 'IN_PROGRESS', true)).toBe(false)
    expect(isGroupMatchDeletable({ phase: 'SEMIFINAL', status: 'SCHEDULED' }, 'IN_PROGRESS', false)).toBe(false)
    expect(isGroupMatchDeletable({ phase: 'GROUP', status: 'SCHEDULED' }, 'FINISHED', false)).toBe(false)
  })
})

describe('GroupStageSection "Generar partidos"', () => {
  it('stays disabled and names the approved teams without a group', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = pathOf(input)
      if (path === '/tournaments/1/groups') return jsonResponse([group([TIGRES, LEONES], 0)])
      if (path === '/tournaments/1/registrations') {
        return jsonResponse([registration(1, 'Tigres'), registration(2, 'Leones'), registration(3, 'Pumas')])
      }
      return jsonResponse([])
    })
    render(
      <MemoryRouter>
        <GroupStageSection tournament={tournament('IN_PROGRESS')} matches={[]} onMatchesChanged={() => undefined} refreshKey={0} />
      </MemoryRouter>,
    )

    const button = await screen.findByRole('button', { name: 'Generar partidos' }, FIND)
    expect(button).toBeDisabled()
    expect(screen.getByTestId('generate-matches-hint')).toHaveTextContent('Asigne todos los equipos aprobados a un grupo. Falta: Pumas.')
  })

  it('generates every group after confirming and refreshes the competition', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = pathOf(input)
      if (path === '/tournaments/1/groups/round-robin' && init?.method === 'POST') {
        return jsonResponse({ created: 1, groups: [], matches: [], message: 'Se creó 1 partido en 1 grupo.' })
      }
      if (path === '/tournaments/1/groups') return jsonResponse([group([TIGRES, LEONES], 0)])
      if (path === '/tournaments/1/registrations') return jsonResponse([registration(1, 'Tigres'), registration(2, 'Leones')])
      return jsonResponse([])
    })
    const onMatchesChanged = vi.fn()
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <GroupStageSection tournament={tournament('IN_PROGRESS')} matches={[]} onMatchesChanged={onMatchesChanged} refreshKey={0} />
      </MemoryRouter>,
    )

    const button = await screen.findByRole('button', { name: 'Generar partidos' }, FIND)
    expect(button).toBeEnabled()
    await user.click(button)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Generar' }))

    await waitFor(() => expect(onMatchesChanged).toHaveBeenCalled())
    expect(
      fetchMock.mock.calls.some(([input, init]) => pathOf(input) === '/tournaments/1/groups/round-robin' && init?.method === 'POST'),
    ).toBe(true)
  })

  it('lists the matches of a group and deletes one without result after confirming', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = pathOf(input)
      if (path === '/tournaments/1/matches/70' && init?.method === 'DELETE') return new Response(null, { status: 204 })
      if (path === '/tournaments/1/groups') return jsonResponse([group([TIGRES, LEONES], 2)])
      if (path === '/tournaments/1/registrations') return jsonResponse([registration(1, 'Tigres'), registration(2, 'Leones')])
      return jsonResponse([])
    })
    const onMatchesChanged = vi.fn()
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <GroupStageSection
          tournament={tournament('IN_PROGRESS')}
          matches={[groupMatch(70, 'SCHEDULED'), { ...groupMatch(71, 'PLAYED'), homeTeam: { id: 2, name: 'Leones', colors: 'azul' }, awayTeam: { id: 1, name: 'Tigres', colors: 'rojo' } }]}
          onMatchesChanged={onMatchesChanged}
          refreshKey={0}
        />
      </MemoryRouter>,
    )

    const list = await screen.findByRole('list', { name: 'Partidos del Grupo A' }, FIND)
    // A played match cannot be deleted (reopen it first): only the scheduled one has the button.
    expect(within(list).getAllByRole('button', { name: /Eliminar el partido/ })).toHaveLength(1)
    await user.click(within(list).getByRole('button', { name: 'Eliminar el partido Tigres vs Leones' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Eliminar' }))

    await waitFor(() => expect(onMatchesChanged).toHaveBeenCalled())
    expect(
      fetchMock.mock.calls.some(([input, init]) => pathOf(input) === '/tournaments/1/matches/70' && init?.method === 'DELETE'),
    ).toBe(true)
  })
})
