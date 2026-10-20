import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TeamDetailPage } from '@/features/teams/pages/TeamDetailPage'
import { useAuthStore } from '@/store/auth.store'
import type { Role, TeamResponse, TournamentResponse, TournamentStatus, UserResponse } from '@/types/api'
import { HardDeleteTournamentDialog } from './components/HardDeleteTournamentDialog'
import { OrganizerPanel } from './components/OrganizerPanel'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function tournament(status: TournamentStatus, suspendedFromStatus: TournamentStatus | null = null): TournamentResponse {
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
    suspendedFromStatus,
  }
}

function renderPanel(value: TournamentResponse, canHardDelete: boolean) {
  return render(
    <MemoryRouter>
      <OrganizerPanel tournament={value} onUpdated={() => undefined} canHardDelete={canHardDelete} />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  // Registrations and matches lists: empty.
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse([]))
})

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

describe('OrganizerPanel suspension and hard delete', () => {
  it('offers "Suspender" on a live tournament and only the admin gets "Eliminar torneo"', () => {
    const { unmount } = renderPanel(tournament('ACTIVE'), false)
    expect(screen.getByRole('button', { name: 'Suspender' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Eliminar torneo' })).not.toBeInTheDocument()
    unmount()

    renderPanel(tournament('ACTIVE'), true)
    expect(screen.getByRole('button', { name: 'Eliminar torneo' })).toBeInTheDocument()
  })

  it('a suspended tournament only offers to resume it and says where it goes back to', () => {
    renderPanel(tournament('SUSPENDED', 'IN_PROGRESS'), false)
    expect(screen.getByRole('button', { name: 'Reanudar torneo' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Suspender' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Finalizar torneo' })).not.toBeInTheDocument()
    expect(screen.getByText(/volverá al estado «En progreso»/)).toBeInTheDocument()
    expect(screen.getByText('El torneo está suspendido; no se pueden agregar canchas.')).toBeInTheDocument()
  })

  it('a finished tournament cannot be suspended', () => {
    renderPanel(tournament('FINISHED'), false)
    expect(screen.queryByRole('button', { name: 'Suspender' })).not.toBeInTheDocument()
  })

  it('confirming the suspension calls the endpoint', async () => {
    const fetchMock = vi.mocked(globalThis.fetch)
    fetchMock.mockImplementation(async (input) =>
      String(input).endsWith('/suspend') ? jsonResponse(tournament('SUSPENDED', 'ACTIVE')) : jsonResponse([]),
    )
    const user = userEvent.setup()
    renderPanel(tournament('ACTIVE'), false)

    await user.click(screen.getByRole('button', { name: 'Suspender' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Suspender' }))

    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([url, init]) => String(url) === '/api/tournaments/1/suspend' && init?.method === 'POST')).toBe(
        true,
      ),
    )
  })
})

describe('HardDeleteTournamentDialog', () => {
  it('stays disabled until the exact tournament name is typed', async () => {
    const onConfirm = vi.fn()
    const user = userEvent.setup()
    render(<HardDeleteTournamentDialog open tournamentName="TechCup 2026-2" onConfirm={onConfirm} onCancel={() => undefined} />)

    const confirm = screen.getByRole('button', { name: 'Eliminar torneo' })
    expect(confirm).toBeDisabled()
    await user.type(screen.getByLabelText(/Escriba «TechCup 2026-2» para confirmar/), 'techcup 2026-2')
    expect(confirm).toBeDisabled()

    await user.clear(screen.getByLabelText(/Escriba «TechCup 2026-2» para confirmar/))
    await user.type(screen.getByLabelText(/Escriba «TechCup 2026-2» para confirmar/), 'TechCup 2026-2')
    expect(confirm).toBeEnabled()
    await user.click(confirm)
    expect(onConfirm).toHaveBeenCalledWith('TechCup 2026-2')
  })
})

describe('TeamDetailPage "Eliminar equipo"', () => {
  const team: TeamResponse = {
    id: 5,
    name: 'Leones FC',
    colors: 'Rojo',
    status: 'ACTIVE',
    captain: { id: 9, fullName: 'Ana Pérez' },
    members: [],
    memberCount: 7,
    locked: false,
  }

  function signIn(roles: Role[]) {
    const me: UserResponse = {
      id: 1,
      fullName: 'Admin',
      email: 'admin@escuelaing.edu.co',
      schoolRelation: null,
      academicProgram: null,
      semester: null,
      status: 'ACTIVE',
      birthDate: null,
      documentType: null,
      documentNumber: null,
      roles,
      hasProfile: false,
      teamId: null,
    }
    useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user: me })
  }

  function renderTeamPage() {
    vi.mocked(globalThis.fetch).mockImplementation(async (input) =>
      String(input).endsWith('/eligibility') ? jsonResponse({ eligible: true, problems: [] }) : jsonResponse(team),
    )
    render(
      <MemoryRouter initialEntries={['/teams/5']}>
        <Routes>
          <Route path="/teams/:id" element={<TeamDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )
  }

  it('is shown to an ADMIN, behind a confirmation', async () => {
    signIn(['ADMIN'])
    renderTeamPage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Eliminar equipo' }))
    expect(screen.getByRole('dialog')).toHaveTextContent('Se eliminará el equipo Leones FC')
  })

  it('is not shown to an organizer or a captain', async () => {
    signIn(['ORGANIZER', 'PLAYER', 'CAPTAIN'])
    renderTeamPage()

    expect(await screen.findByRole('heading', { name: 'Leones FC' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Eliminar equipo' })).not.toBeInTheDocument()
  })
})
