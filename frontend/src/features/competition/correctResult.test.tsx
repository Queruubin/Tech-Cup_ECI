import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth.store'
import type { MatchResponse, TeamResponse, TournamentResponse, UserResponse } from '@/types/api'
import { MatchDetailPage } from './pages/MatchDetailPage'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const MATCH: MatchResponse = {
  id: 7,
  tournamentId: 1,
  phase: 'SEMIFINAL',
  roundNumber: 4,
  group: null,
  homeTeam: { id: 10, name: 'Leones FC', colors: 'Rojo' },
  awayTeam: { id: 20, name: 'Aguilas FC', colors: 'Azul' },
  venue: null,
  referee: null,
  scheduledAt: '2026-03-10T23:00:00Z',
  status: 'PLAYED',
  homeScore: 2,
  awayScore: 1,
  homePenalties: null,
  awayPenalties: null,
  cancelReason: null,
  walkoverWinnerTeamId: null,
  resultEditable: true,
  teamsEditable: false,
  reopenable: true,
  events: [
    { id: 1, teamId: 10, playerId: 101, playerName: 'Daniel Soto', type: 'GOAL', minute: 10 },
    { id: 2, teamId: 10, playerId: 101, playerName: 'Daniel Soto', type: 'GOAL', minute: 20 },
    { id: 3, teamId: 20, playerId: 201, playerName: 'Mateo Vargas', type: 'GOAL', minute: 30 },
  ],
}

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

beforeEach(() => {
  const me: UserResponse = {
    id: 1,
    fullName: 'Organizadora',
    email: 'org@escuelaing.edu.co',
    schoolRelation: null,
    academicProgram: null,
    semester: null,
    status: 'ACTIVE',
    birthDate: null,
    documentType: null,
    documentNumber: null,
    roles: ['ORGANIZER'],
    hasProfile: false,
    teamId: null,
  }
  useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user: me })
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input)
    if (url.endsWith('/matches/7')) return jsonResponse(MATCH)
    if (url.endsWith('/tournaments/1')) return jsonResponse(TOURNAMENT)
    if (url.endsWith('/teams/10')) return jsonResponse(team(10, 'Leones FC', 101, 'Daniel Soto'))
    if (url.endsWith('/teams/20')) return jsonResponse(team(20, 'Aguilas FC', 201, 'Mateo Vargas'))
    return jsonResponse([])
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

describe('MatchDetailPage "Corregir resultado"', () => {
  it('a played match offers the correction, which opens the result form prefilled', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/matches/7']}>
        <Routes>
          <Route path="/matches/:id" element={<MatchDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )

    const open = await screen.findByRole('button', { name: 'Corregir resultado' })
    expect(screen.queryByLabelText(/Goles local/i)).toBeNull()
    await user.click(open)

    const form = (await screen.findByRole('heading', { name: 'Corregir resultado' })).closest('section') as HTMLElement
    const inputs = within(form).getAllByRole('spinbutton')
    expect(inputs[0]).toHaveValue(2)
    expect(inputs[1]).toHaveValue(1)
    expect(within(form).getByRole('button', { name: 'Corregir resultado' })).toBeInTheDocument()
    // Reopening is still available as the other way to fix a match.
    expect(screen.getByRole('button', { name: 'Reabrir partido' })).toBeInTheDocument()
  })
})
