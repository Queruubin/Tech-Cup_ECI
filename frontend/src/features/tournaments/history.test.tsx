import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth.store'
import type { Role, TournamentHistory, TournamentHistorySummary, TournamentResponse, UserResponse } from '@/types/api'
import { HistoryTab } from './components/HistoryTab'
import { PublicHistoryPage } from './pages/PublicHistoryPage'
import { OrganizerPanel } from './components/OrganizerPanel'
import { goalsPerMatch, podiumLabel, sanctionCandidates } from './history'
import { TournamentDetailPage } from './pages/TournamentDetailPage'
import { TournamentsPage } from './pages/TournamentsPage'

/** Public history of a finished tournament, its staff controls, and how staff get there. */

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
    approvedTeams: 4,
    suspendedFromStatus: null,
    formUrl: null,
  }
}

const HISTORY: TournamentHistory = {
  tournamentId: 1,
  name: 'TechCup 2026-2',
  startDate: '2026-09-01',
  endDate: '2026-11-30',
  podiumSource: 'KNOCKOUT',
  podium: [
    { position: 1, teamId: 1, teamName: 'Tigres', played: 5, points: 13, goalDifference: 8, goalsFor: 11, goalsAgainst: 3, photoFileId: 'photo-1' },
    { position: 2, teamId: 2, teamName: 'Leones', played: 5, points: 9, goalDifference: 3, goalsFor: 8, goalsAgainst: 5, photoFileId: null },
    { position: 3, teamId: 3, teamName: 'Pumas', played: 4, points: 7, goalDifference: 2, goalsFor: 6, goalsAgainst: 4, photoFileId: null },
  ],
  thirdPlaceCandidates: [
    { teamId: 3, teamName: 'Pumas', played: 4, points: 7, goalDifference: 2, goalsFor: 6, goalsAgainst: 4 },
    { teamId: 4, teamName: 'Cóndores', played: 4, points: 7, goalDifference: 1, goalsFor: 7, goalsAgainst: 6 },
  ],
  bestDefence: { teamId: 1, teamName: 'Tigres', goalsConceded: 3, matchesPlayed: 5 },
  topScorers: [
    { playerId: 7, playerName: 'Ana Pérez', teamId: 1, teamName: 'Tigres', goals: 4 },
    { playerId: 8, playerName: 'Luis Gómez', teamId: 2, teamName: 'Leones', goals: 4 },
  ],
  sanctionedTeams: [
    {
      id: 30,
      teamId: null,
      teamName: 'Halcones',
      note: 'Agresión al árbitro',
      createdAt: '2026-11-30T15:00:00Z',
      members: [{ fullName: 'Pedro Ruiz', captain: true }],
    },
  ],
  participants: [
    { id: 4, name: 'Cóndores' },
    { id: 2, name: 'Leones' },
    { id: 3, name: 'Pumas' },
    { id: 1, name: 'Tigres' },
  ],
}

function pathOf(input: RequestInfo | URL): string {
  return new URL(String(input), 'http://localhost').pathname.replace(/^\/api/, '')
}

function signIn(roles: Role[]) {
  const user: UserResponse = {
    id: 1,
    fullName: 'Staff',
    email: 'staff@mail.com',
    schoolRelation: null,
    academicProgram: 'OTHER',
    semester: null,
    status: 'ACTIVE',
    birthDate: null,
    documentType: null,
    documentNumber: null,
    roles,
    hasProfile: false,
    teamId: null,
  } as unknown as UserResponse
  useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user })
}

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

describe('history helpers', () => {
  it('names the podium positions and computes the best-defence ratio', () => {
    expect([1, 2, 3].map(podiumLabel)).toEqual(['Campeón', 'Subcampeón', '3er puesto'])
    expect(goalsPerMatch(3, 5)).toBe('0,6')
    expect(goalsPerMatch(0, 0)).toBe('0')
  })

  it('offers only the participants that are not sanctioned yet', () => {
    const history = { ...HISTORY, sanctionedTeams: [{ ...HISTORY.sanctionedTeams[0]!, teamId: 4 }] }
    expect(sanctionCandidates(history).map((team) => team.name)).toEqual(['Leones', 'Pumas', 'Tigres'])
  })
})

describe('HistoryTab', () => {
  it('shows the podium, best defence, tied top scorers and sanctioned teams to anybody, without staff controls', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse(HISTORY))
    render(
      <MemoryRouter>
        <HistoryTab tournamentId={1} canManage={false} />
      </MemoryRouter>,
    )

    const champion = await screen.findByTestId('podium-1', {}, FIND)
    expect(within(champion).getByText('Campeón')).toBeInTheDocument()
    expect(within(champion).getByRole('button', { name: /Tigres/ })).toBeInTheDocument()
    expect(within(screen.getByTestId('podium-2')).getByText('Leones')).toBeInTheDocument()
    expect(within(screen.getByTestId('podium-3')).getByText('Pumas')).toBeInTheDocument()
    expect(screen.getByText('Semifinalistas eliminados')).toBeInTheDocument()
    expect(screen.getByText(/3 goles recibidos en 5 partidos/)).toBeInTheDocument()
    const scorers = screen.getByRole('list', { name: 'Goleadores' })
    expect(within(scorers).getByText('Ana Pérez')).toBeInTheDocument()
    expect(within(scorers).getByText('Luis Gómez')).toBeInTheDocument()
    expect(screen.getByText('Halcones')).toBeInTheDocument()
    expect(screen.getByText('(equipo eliminado)')).toBeInTheDocument()

    expect(screen.queryByRole('button', { name: /Agregar foto|Cambiar foto/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Agregar sanción' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Retirar la sanción/ })).not.toBeInTheDocument()
  })

  it('opens the podium photo of a team in a modal through the public URL', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse(HISTORY))
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <HistoryTab tournamentId={1} canManage={false} />
      </MemoryRouter>,
    )

    await user.click(await screen.findByRole('button', { name: /Tigres/ }, FIND))

    const image = within(await screen.findByRole('dialog')).getByRole('img')
    expect(image.getAttribute('src')).toBe('/api/tournaments/1/history/podium/1/photo?v=photo-1')
  })

  it('lets staff add a sanctioned team and reloads the history', async () => {
    let history = HISTORY
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      if (pathOf(input) === '/tournaments/1/history/sanctions' && init?.method === 'POST') {
        const created = { id: 31, teamId: 4, teamName: 'Cóndores', note: 'Alineación indebida', createdAt: '2026-12-01T10:00:00Z', members: [] }
        history = { ...HISTORY, sanctionedTeams: [...HISTORY.sanctionedTeams, created] }
        return jsonResponse(created, 201)
      }
      return jsonResponse(history)
    })
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <HistoryTab tournamentId={1} canManage justFinished />
      </MemoryRouter>,
    )

    expect(await screen.findByText(/agregue ahora los equipos sancionados y las fotos del podio/, {}, FIND)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /Agregar foto|Cambiar foto/ })).toHaveLength(3)
    await user.selectOptions(screen.getByLabelText('Equipo'), 'Cóndores')
    await user.type(screen.getByLabelText('Nota (opcional)'), 'Alineación indebida')
    await user.click(screen.getByRole('button', { name: 'Agregar sanción' }))

    const list = await screen.findByRole('list', { name: 'Equipos sancionados' })
    await waitFor(() => expect(within(list).getByText('Cóndores')).toBeInTheDocument())
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ teamId: 4, note: 'Alineación indebida' })
  })
})

describe('reaching the history', () => {
  it('finishing the tournament from "Gestión" navigates to its history with the hint', async () => {
    signIn(['ORGANIZER'])
    let status: TournamentResponse['status'] = 'IN_PROGRESS'
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = pathOf(input)
      if (path === '/tournaments/1/finish' && init?.method === 'POST') {
        status = 'FINISHED'
        return jsonResponse(tournament('FINISHED'))
      }
      if (path === '/tournaments/1') return jsonResponse(tournament(status))
      if (path === '/tournaments/1/history') return jsonResponse(HISTORY)
      return jsonResponse([])
    })
    function Probe() {
      const location = useLocation()
      return <p data-testid="location">{location.pathname + location.search}</p>
    }
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/tournaments/1?tab=manage']}>
        <Routes>
          <Route
            path="/tournaments/:id"
            element={
              <>
                <TournamentDetailPage />
                <Probe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>,
    )

    await user.click(await screen.findByRole('button', { name: 'Finalizar torneo' }, FIND))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Finalizar' }))

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/tournaments/1?tab=history'))
    expect(await screen.findByRole('tab', { name: 'Historial', selected: true }, FIND)).toBeInTheDocument()
    expect(await screen.findByText(/agregue ahora los equipos sancionados/, {}, FIND)).toBeInTheDocument()
  })

  it('the panel alone navigates to the history path after finishing', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) =>
      pathOf(input) === '/tournaments/1/finish' && init?.method === 'POST' ? jsonResponse(tournament('FINISHED')) : jsonResponse([]),
    )
    function Probe() {
      const location = useLocation()
      return <p data-testid="location">{location.pathname + location.search}</p>
    }
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/tournaments/1?tab=manage']}>
        <OrganizerPanel tournament={tournament('IN_PROGRESS')} onUpdated={() => undefined} />
        <Probe />
      </MemoryRouter>,
    )

    await user.click(screen.getByRole('button', { name: 'Finalizar torneo' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Finalizar' }))

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/tournaments/1?tab=history'))
  })

  it('anonymous visitors read the list and a history on the public pages, without staff controls', async () => {
    const past: TournamentHistorySummary[] = [
      { id: 1, name: 'TechCup 2026-2', startDate: '2026-09-01', endDate: '2026-11-30', podiumSource: 'KNOCKOUT', champion: { teamId: 1, teamName: 'Tigres' } },
    ]
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) =>
      pathOf(input) === '/tournaments/history' ? jsonResponse(past) : jsonResponse(HISTORY),
    )
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/history']}>
        <Routes>
          <Route path="/history" element={<PublicHistoryPage />} />
          <Route path="/history/:id" element={<PublicHistoryPage />} />
        </Routes>
      </MemoryRouter>,
    )

    await user.click(await screen.findByRole('link', { name: /TechCup 2026-2/ }, FIND))
    expect(await screen.findByRole('heading', { name: 'TechCup 2026-2' }, FIND)).toBeInTheDocument()
    expect(screen.getByTestId('podium-1')).toHaveTextContent('Tigres')
    expect(screen.queryByRole('button', { name: 'Agregar sanción' })).not.toBeInTheDocument()
    // No session: no Authorization header is sent.
    for (const [, init] of fetchMock.mock.calls) expect(new Headers(init?.headers).get('Authorization')).toBeNull()
  })

  it('"Torneos anteriores" lists the finished tournaments with their champion, linking to the history', async () => {
    const past: TournamentHistorySummary[] = [
      { id: 1, name: 'TechCup 2026-2', startDate: '2026-09-01', endDate: '2026-11-30', podiumSource: 'KNOCKOUT', champion: { teamId: 1, teamName: 'Tigres' } },
      { id: 2, name: 'TechCup 2026-1', startDate: '2026-02-01', endDate: '2026-05-30', podiumSource: null, champion: null },
    ]
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = pathOf(input)
      if (path === '/tournaments/history') return jsonResponse(past)
      if (path === '/tournaments') return jsonResponse([tournament('FINISHED')])
      return jsonResponse(null, 404)
    })
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/tournaments']}>
        <TournamentsPage />
      </MemoryRouter>,
    )

    // A finished tournament card already leads to its history.
    const card = await screen.findByRole('link', { name: /TechCup 2026-2/ }, FIND)
    expect(card).toHaveAttribute('href', '/tournaments/1?tab=history')

    await user.click(screen.getByRole('tab', { name: 'Torneos anteriores' }))
    const champion = await screen.findByText('Tigres', {}, FIND)
    expect(champion.closest('a')).toHaveAttribute('href', '/tournaments/1?tab=history')
    expect(screen.getByText('Sin campeón')).toBeInTheDocument()
  })
})
