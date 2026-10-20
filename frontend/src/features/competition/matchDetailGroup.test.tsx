import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth.store'
import type { GroupResponse, MatchResponse, TeamResponse, TournamentResponse, UserResponse } from '@/types/api'
import { GROUP_STAGE_CLOSED_HINT, groupTeamOptions } from './matchEdit'
import { MatchDetailPage } from './pages/MatchDetailPage'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const GROUP_MATCH: MatchResponse = {
  id: 7,
  tournamentId: 1,
  phase: 'GROUP',
  roundNumber: 1,
  group: { id: 5, name: 'Grupo A' },
  homeTeam: { id: 10, name: 'Leones FC', colors: 'Rojo' },
  awayTeam: { id: 20, name: 'Aguilas FC', colors: 'Azul' },
  venue: null,
  referee: null,
  scheduledAt: '2026-03-10T23:00:00Z',
  status: 'PLAYED',
  homeScore: 1,
  awayScore: 0,
  homePenalties: null,
  awayPenalties: null,
  cancelReason: null,
  walkoverWinnerTeamId: null,
  resultEditable: true,
  teamsEditable: false,
  reopenable: true,
  events: [{ id: 1, teamId: 10, playerId: 101, playerName: 'Daniel Soto', type: 'GOAL', minute: 10 }],
}

const GROUPS: GroupResponse[] = [
  {
    id: 5,
    name: 'Grupo A',
    position: 0,
    teams: [
      { id: 10, name: 'Leones FC', colors: 'Rojo', hasMatches: true },
      { id: 20, name: 'Aguilas FC', colors: 'Azul', hasMatches: true },
      { id: 30, name: 'Pumas FC', colors: 'Verde', hasMatches: false },
    ],
    matchCount: 1,
  },
  {
    id: 6,
    name: 'Grupo B',
    position: 1,
    teams: [{ id: 40, name: 'Osos FC', colors: 'Negro', hasMatches: false }],
    matchCount: 0,
  },
]

const TOURNAMENT = { id: 1, name: 'TechCup', status: 'IN_PROGRESS', venues: [] } as unknown as TournamentResponse

function team(id: number, name: string, playerId: number, playerName: string): TeamResponse {
  return {
    id,
    name,
    colors: 'Rojo',
    status: 'ACTIVE',
    captain: { id: playerId, fullName: playerName },
    members: [
      { userId: playerId, fullName: playerName, position: 'FORWARD', jerseyNumber: 9, academicProgram: 'SYSTEMS_ENGINEERING', photoFileId: null },
    ],
    memberCount: 7,
    locked: true,
  } as TeamResponse
}

let match: MatchResponse = GROUP_MATCH
let tournamentMatches: Array<Pick<MatchResponse, 'id' | 'phase' | 'status'>> = []
let resultStatus = 409

beforeEach(() => {
  match = GROUP_MATCH
  tournamentMatches = []
  resultStatus = 409
  const me = { id: 1, fullName: 'Organizadora', roles: ['ORGANIZER'], hasProfile: false, teamId: null } as unknown as UserResponse
  useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user: me })
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = new URL(String(input), 'http://localhost').pathname.replace(/^\/api/, '')
    const method = (init?.method ?? 'GET').toUpperCase()
    if (method === 'POST' && path === '/matches/7/result') {
      return jsonResponse({ status: resultStatus, message: 'Faltan goleadores del equipo local.' }, resultStatus)
    }
    if (path === '/matches/7') return jsonResponse(match)
    if (path === '/tournaments/1') return jsonResponse(TOURNAMENT)
    if (path === '/tournaments/1/groups') return jsonResponse(GROUPS)
    if (path === '/tournaments/1/matches') return jsonResponse(tournamentMatches)
    if (path === '/teams/10') return jsonResponse(team(10, 'Leones FC', 101, 'Daniel Soto'))
    if (path === '/teams/20') return jsonResponse(team(20, 'Aguilas FC', 201, 'Mateo Vargas'))
    return jsonResponse([])
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/matches/7']}>
      <Routes>
        <Route path="/matches/:id" element={<MatchDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('groupTeamOptions', () => {
  it('offers only the teams of the match group, keeping the current ones', () => {
    expect(groupTeamOptions(GROUPS, 5, [{ id: 10, name: 'Leones FC' }]).map((option) => option.label)).toEqual([
      'Aguilas FC',
      'Leones FC',
      'Pumas FC',
    ])
    // A current team that left the group stays selectable so the select never goes blank.
    expect(groupTeamOptions(GROUPS, 6, [{ id: 99, name: 'Zorros FC' }]).map((option) => option.value)).toEqual(['40', '99'])
  })
})

describe('MatchDetailPage for a group match', () => {
  it('the team selects only offer the teams of the match group', async () => {
    match = { ...GROUP_MATCH, status: 'SCHEDULED', homeScore: null, awayScore: null, teamsEditable: true, reopenable: false, events: [] }
    const user = userEvent.setup()
    renderPage()

    // "Editar" waits for the tournament status (a second request); the full suite runs in
    // parallel and can take longer than the default 1 s.
    await user.click(await screen.findByRole('button', { name: 'Editar' }, { timeout: 5000 }))
    const home = await screen.findByLabelText('Equipo local', {}, { timeout: 5000 })
    const labels = within(home).getAllByRole('option').map((option) => option.textContent)
    expect(labels).toEqual(expect.arrayContaining(['Leones FC', 'Aguilas FC', 'Pumas FC']))
    expect(labels).not.toContain('Osos FC')
  })

  it('reopening a group match is disabled once the knockout exists', async () => {
    tournamentMatches = [
      { id: 7, phase: 'GROUP', status: 'PLAYED' },
      { id: 90, phase: 'FINAL', status: 'SCHEDULED' },
    ]
    renderPage()

    const reopen = await screen.findByRole('button', { name: 'Reabrir partido' })
    await waitFor(() => expect(reopen).toBeDisabled())
    expect(reopen).toHaveAttribute('title', GROUP_STAGE_CLOSED_HINT)
  })

  it('"Corregir resultado" opens without the error of an earlier failed attempt', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Corregir resultado' }))
    const form = (await screen.findByRole('heading', { name: 'Corregir resultado' })).closest('section') as HTMLElement
    await user.click(within(form).getByRole('button', { name: 'Corregir resultado' }))
    expect(await screen.findByText('Faltan goleadores del equipo local.')).toBeInTheDocument()

    await user.click(within(form).getByRole('button', { name: 'Cerrar' }))
    await user.click(await screen.findByRole('button', { name: 'Corregir resultado' }))

    await screen.findByRole('heading', { name: 'Corregir resultado' })
    expect(screen.queryByText('Faltan goleadores del equipo local.')).toBeNull()
  })
})
