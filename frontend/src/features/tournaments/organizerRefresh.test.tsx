import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { GroupResponse, MatchResponse, RegistrationResponse, TournamentResponse } from '@/types/api'
import { GroupsManager } from './components/GroupsManager'
import { OrganizerPanel } from './components/OrganizerPanel'

/** The organizer's "Gestión" tab keeps its groups card in step with every change made from the panel. */

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
    approvedTeams: 2,
    suspendedFromStatus: null,
  }
}

function registration(teamId: number, teamName: string, status: RegistrationResponse['status']): RegistrationResponse {
  return {
    id: teamId * 10,
    tournamentId: 1,
    teamId,
    teamName,
    receiptFileId: null,
    status,
    reviewNote: null,
    createdAt: '2026-08-01T10:00:00Z',
    reviewedAt: null,
  } as RegistrationResponse
}

function groupA(matchCount: number): GroupResponse {
  return {
    id: 5,
    name: 'Grupo A',
    position: 0,
    teams: [
      { id: 1, name: 'Tigres', colors: 'rojo', hasMatches: matchCount > 0 },
      { id: 2, name: 'Leones', colors: 'azul', hasMatches: matchCount > 0 },
    ],
    matchCount,
  }
}

const GROUP_MATCH = {
  id: 70,
  tournamentId: 1,
  phase: 'GROUP',
  roundNumber: 1,
  group: { id: 5, name: 'Grupo A' },
  homeTeam: { id: 1, name: 'Tigres', colors: 'rojo' },
  awayTeam: { id: 2, name: 'Leones', colors: 'azul' },
  status: 'SCHEDULED',
  events: [],
} as unknown as MatchResponse

/** Routes the API calls by method and path; `state` changes as the mutations succeed. */
function mockApi(handler: (method: string, path: string) => Response | undefined) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = new URL(String(input), 'http://localhost').pathname.replace(/^\/api/, '')
    return handler((init?.method ?? 'GET').toUpperCase(), path) ?? jsonResponse([])
  })
}

function callsTo(fetchMock: ReturnType<typeof mockApi>, path: string): number {
  return fetchMock.mock.calls.filter(([input, init]) => {
    const called = new URL(String(input), 'http://localhost').pathname.replace(/^\/api/, '')
    return called === path && (init?.method ?? 'GET').toUpperCase() === 'GET'
  }).length
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('OrganizerPanel keeps the groups card fresh', () => {
  it('an approved registration shows up among the teams without a group', async () => {
    let status: RegistrationResponse['status'] = 'UNDER_REVIEW'
    const fetchMock = mockApi((method, path) => {
      if (method === 'POST' && path === '/registrations/10/approve') {
        status = 'APPROVED'
        return jsonResponse(registration(1, 'Tigres', 'APPROVED'))
      }
      if (path === '/tournaments/1/registrations') return jsonResponse([registration(1, 'Tigres', status)])
      if (path === '/tournaments/1') return jsonResponse(tournament('ACTIVE'))
      return undefined
    })
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <OrganizerPanel tournament={tournament('ACTIVE')} onUpdated={() => undefined} />
      </MemoryRouter>,
    )

    expect(await screen.findByText('Todos los equipos aprobados tienen grupo.')).toBeInTheDocument()
    const groupLoads = callsTo(fetchMock, '/tournaments/1/groups')

    await user.click(await screen.findByRole('button', { name: 'Aprobar' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Aprobar' }))

    const unassigned = await screen.findByRole('list', { name: 'Equipos sin grupo' })
    expect(within(unassigned).getByText('Tigres')).toBeInTheDocument()
    expect(callsTo(fetchMock, '/tournaments/1/groups')).toBeGreaterThan(groupLoads)
  })

  it('undoing the last phase reloads the groups (match counts, teams that can move again)', async () => {
    let undone = false
    mockApi((method, path) => {
      if (method === 'POST' && path === '/tournaments/1/matches/undo-phase') {
        undone = true
        return jsonResponse({ phase: 'GROUP', deletedMatches: 1 })
      }
      if (path === '/tournaments/1/matches') return jsonResponse(undone ? [] : [GROUP_MATCH])
      if (path === '/tournaments/1/groups') return jsonResponse([groupA(undone ? 0 : 1)])
      if (path === '/tournaments/1/registrations') {
        return jsonResponse([registration(1, 'Tigres', 'APPROVED'), registration(2, 'Leones', 'APPROVED')])
      }
      return undefined
    })
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <OrganizerPanel tournament={tournament('IN_PROGRESS')} onUpdated={() => undefined} />
      </MemoryRouter>,
    )

    const group = await screen.findByRole('region', { name: 'Grupo A' })
    expect(within(group).getByText(/2 equipos · 1 partido$/)).toBeInTheDocument()
    expect(within(group).getByRole('button', { name: 'Quitar Tigres del Grupo A' })).toBeDisabled()

    await user.click(await screen.findByRole('button', { name: 'Deshacer última fase' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Deshacer fase' }))

    await waitFor(() =>
      expect(within(screen.getByRole('region', { name: 'Grupo A' })).getByText(/2 equipos · 0 partidos/)).toBeInTheDocument(),
    )
    expect(screen.getByRole('button', { name: 'Quitar Tigres del Grupo A' })).toBeEnabled()
  })
})

describe('GroupsManager team choice', () => {
  const GROUPS: GroupResponse[] = [
    { id: 5, name: 'Grupo A', position: 0, teams: [], matchCount: 0 },
    { id: 6, name: 'Grupo B', position: 1, teams: [], matchCount: 0 },
  ]

  function manager(unassigned: { id: number; name: string }[]) {
    return (
      <GroupsManager
        tournamentId={1}
        groups={GROUPS}
        unassigned={unassigned}
        groupsEditable
        matchesEditable={false}
        onChanged={() => undefined}
      />
    )
  }

  it('drops a choice whose team is no longer without a group instead of assigning it', async () => {
    const user = userEvent.setup()
    const { rerender } = render(manager([{ id: 8, name: 'Lobos' }, { id: 9, name: 'Zorros' }]))

    await user.selectOptions(screen.getByRole('combobox', { name: 'Equipo para el Grupo B' }), '9')
    expect(within(screen.getByRole('region', { name: 'Grupo B' })).getByRole('button', { name: 'Agregar' })).toBeEnabled()

    // Zorros got a group elsewhere (another tab, a reload): the stale choice must not count.
    rerender(manager([{ id: 8, name: 'Lobos' }]))

    expect(screen.getByRole('combobox', { name: 'Equipo para el Grupo B' })).toHaveValue('')
    expect(within(screen.getByRole('region', { name: 'Grupo B' })).getByRole('button', { name: 'Agregar' })).toBeDisabled()
  })

  it('clears every choice after a successful assignment', async () => {
    mockApi((method) => (method === 'PUT' ? jsonResponse(GROUPS[0]) : undefined))
    const user = userEvent.setup()
    render(manager([{ id: 8, name: 'Lobos' }, { id: 9, name: 'Zorros' }]))

    await user.selectOptions(screen.getByRole('combobox', { name: 'Equipo para el Grupo A' }), '8')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Equipo para el Grupo B' }), '9')
    await user.click(within(screen.getByRole('region', { name: 'Grupo A' })).getByRole('button', { name: 'Agregar' }))

    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Equipo para el Grupo B' })).toHaveValue(''))
    expect(screen.getByRole('combobox', { name: 'Equipo para el Grupo A' })).toHaveValue('')
  })
})
