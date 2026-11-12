import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth.store'
import { useUiStore } from '@/store/ui.store'
import type { Role, TeamMember, TeamResponse, UserResponse } from '@/types/api'
import { MyTeamPage } from './pages/MyTeamPage'
import { TeamDetailPage } from './pages/TeamDetailPage'
import { LEAVE_FROZEN_HINT, ROSTER_FROZEN_NOTICE, TEAM_LOCKED_NOTICE } from './rosterLock'
import { MAX_TEAM_MEMBERS, MIN_TEAM_MEMBERS, TEAM_SIZE_RULE, rosterCount } from './teamSize'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function member(userId: number, fullName: string): TeamMember {
  return { userId, fullName, position: 'DEFENDER', jerseyNumber: userId, academicProgram: 'SYSTEMS_ENGINEERING', photoFileId: null }
}

const CAPTAIN_ID = 1
const MEMBER_ID = 2

function makeTeam(overrides: Partial<TeamResponse> = {}): TeamResponse {
  return {
    id: 4,
    name: 'Leones FC',
    colors: 'Rojo',
    status: 'ACTIVE',
    captain: { id: CAPTAIN_ID, fullName: 'Ana Pérez' },
    members: [member(CAPTAIN_ID, 'Ana Pérez'), member(MEMBER_ID, 'Pedro Gómez')],
    memberCount: 2,
    locked: false,
    photoFileId: null,
    ...overrides,
  }
}

function me(id: number, teamId: number | null, roles: Role[] = ['PLAYER']): UserResponse {
  return {
    id,
    fullName: id === CAPTAIN_ID ? 'Ana Pérez' : 'Pedro Gómez',
    email: 'pedro@escuelaing.edu.co',
    phoneNumber: null,
    schoolRelation: 'STUDENT',
    academicProgram: 'SYSTEMS_ENGINEERING',
    semester: 5,
    status: 'ACTIVE',
    birthDate: null,
    documentType: null,
    documentNumber: null,
    roles,
    hasProfile: true,
    teamId,
  }
}

function signIn(user: UserResponse) {
  useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user })
}

/** Serves `GET /teams/mine` and `GET /teams/4` from `team`, records every request. */
function serve(team: TeamResponse | null, afterLeave: UserResponse) {
  const calls: string[] = []
  let left = false
  vi.mocked(globalThis.fetch).mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push(`${method} ${url.replace(/^.*\/api/, '')}`)
    if (method === 'POST' && url.endsWith('/teams/4/leave')) {
      left = true
      return new Response(null, { status: 204 })
    }
    if (url.endsWith('/auth/me')) return jsonResponse(afterLeave)
    if (url.endsWith('/eligibility')) return jsonResponse({ eligible: false, problems: [] })
    if (url.endsWith('/teams/mine')) {
      return left || !team ? jsonResponse({ status: 404, message: 'Usted no pertenece a ningún equipo activo.' }, 404) : jsonResponse(team)
    }
    if (url.endsWith('/teams/4') && team) {
      return jsonResponse(left ? { ...team, members: team.members.filter((m) => m.userId !== MEMBER_ID), memberCount: 1 } : team)
    }
    return jsonResponse([])
  })
  return calls
}

// Pages load in several requests and these tests click through dialogs: allow for a busy runner.
vi.setConfig({ testTimeout: 15_000 })

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse([]))
})

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

function renderMyTeam() {
  return render(
    <MemoryRouter initialEntries={['/my-team']}>
      <MyTeamPage />
    </MemoryRouter>,
  )
}

describe('"Salir del equipo" on Mi equipo', () => {
  it('lets a member leave after confirming, then reloads the session and shows the no-team state', async () => {
    signIn(me(MEMBER_ID, 4))
    const calls = serve(makeTeam(), me(MEMBER_ID, null))
    renderMyTeam()

    const button = await screen.findByRole('button', { name: 'Salir del equipo' }, { timeout: 3000 })
    expect(button).toBeEnabled()
    const user = userEvent.setup()
    await user.click(button)

    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent(
      '¿Seguro que quiere salir de «Leones FC»? Tendrá que volver a pedir unirse o recibir una invitación para regresar.',
    )
    expect(calls).not.toContain('POST /teams/4/leave')
    await user.click(within(dialog).getByRole('button', { name: 'Salir del equipo' }))

    expect(await screen.findByRole('heading', { name: 'Crear equipo' }, { timeout: 3000 })).toBeInTheDocument()
    expect(calls).toContain('POST /teams/4/leave')
    expect(calls.indexOf('GET /auth/me')).toBeGreaterThan(calls.indexOf('POST /teams/4/leave'))
    await waitFor(() => expect(useAuthStore.getState().user?.teamId).toBeNull())
  })

  it('keeps the button visible but disabled, with the reason, while the team is locked', async () => {
    signIn(me(MEMBER_ID, 4))
    serve(makeTeam({ locked: true }), me(MEMBER_ID, 4))
    renderMyTeam()

    const button = await screen.findByRole('button', { name: 'Salir del equipo' }, { timeout: 3000 })
    expect(button).toBeDisabled()
    expect(screen.getByText(LEAVE_FROZEN_HINT)).toBeInTheDocument()
    expect(button).toHaveAttribute('aria-describedby', screen.getByText(LEAVE_FROZEN_HINT).id)
  })

  it('is not offered to the captain', async () => {
    signIn(me(CAPTAIN_ID, 4, ['PLAYER', 'CAPTAIN']))
    serve(makeTeam(), me(CAPTAIN_ID, 4))
    renderMyTeam()

    expect(await screen.findByRole('heading', { name: 'Leones FC' }, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Salir del equipo' })).not.toBeInTheDocument()
  })

  it('does nothing when the member cancels the confirmation', async () => {
    signIn(me(MEMBER_ID, 4))
    const calls = serve(makeTeam(), me(MEMBER_ID, null))
    renderMyTeam()

    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Salir del equipo' }, { timeout: 3000 }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Volver' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(calls).not.toContain('POST /teams/4/leave')
  })
})

describe('"Salir del equipo" on the team page', () => {
  function renderTeamPage() {
    return render(
      <MemoryRouter initialEntries={['/teams/4']}>
        <Routes>
          <Route path="/teams/:id" element={<TeamDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )
  }

  it('is offered to a member and leaving refreshes the roster', async () => {
    signIn(me(MEMBER_ID, 4))
    const calls = serve(makeTeam(), me(MEMBER_ID, null))
    renderTeamPage()

    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Salir del equipo' }, { timeout: 3000 }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Salir del equipo' }))

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Salir del equipo' })).not.toBeInTheDocument(), {
      timeout: 3000,
    })
    expect(calls).toContain('POST /teams/4/leave')
    expect(screen.queryByText('Pedro Gómez')).not.toBeInTheDocument()
  })

  it('is not offered to somebody outside the team nor to its captain', async () => {
    signIn(me(7, null))
    serve(makeTeam(), me(7, null))
    const { unmount } = renderTeamPage()
    expect(await screen.findByRole('heading', { name: 'Leones FC' }, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Salir del equipo' })).not.toBeInTheDocument()
    unmount()

    signIn(me(CAPTAIN_ID, 4, ['PLAYER', 'CAPTAIN']))
    serve(makeTeam(), me(CAPTAIN_ID, 4))
    renderTeamPage()
    expect(await screen.findByRole('heading', { name: 'Leones FC' }, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Salir del equipo' })).not.toBeInTheDocument()
  })
})

describe('roster controls follow the tournament start, not the approval', () => {
  it('lets the captain of a team approved in a not-started tournament (not locked) remove and invite', async () => {
    signIn(me(CAPTAIN_ID, 4, ['PLAYER', 'CAPTAIN']))
    serve(makeTeam({ locked: false }), me(CAPTAIN_ID, 4))
    renderMyTeam()

    const remove = await screen.findByRole('button', { name: 'Retirar' }, { timeout: 3000 })
    expect(remove).toBeEnabled()
    expect(screen.getAllByRole('button', { name: 'Ver jugadores disponibles' }).length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: 'Editar equipo' })).toBeEnabled()
    expect(screen.queryByText(TEAM_LOCKED_NOTICE)).not.toBeInTheDocument()
    expect(screen.queryByText(ROSTER_FROZEN_NOTICE)).not.toBeInTheDocument()
  })

  it('lets a member of such a team leave', async () => {
    signIn(me(MEMBER_ID, 4))
    serve(makeTeam({ locked: false }), me(MEMBER_ID, 4))
    renderMyTeam()

    expect(await screen.findByRole('button', { name: 'Salir del equipo' }, { timeout: 3000 })).toBeEnabled()
    expect(screen.queryByText(LEAVE_FROZEN_HINT)).not.toBeInTheDocument()
  })

  it('freezes everything once the tournament has started (locked)', async () => {
    signIn(me(CAPTAIN_ID, 4, ['PLAYER', 'CAPTAIN']))
    serve(makeTeam({ locked: true }), me(CAPTAIN_ID, 4))
    renderMyTeam()

    expect(await screen.findByText(TEAM_LOCKED_NOTICE, {}, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retirar' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Editar equipo' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Ver jugadores disponibles' })).not.toBeInTheDocument()
  })

  it('shows a removal refused by the minimum size as a normal error message', async () => {
    signIn(me(CAPTAIN_ID, 4, ['PLAYER', 'CAPTAIN']))
    const refusal =
      "El equipo 'Leones FC' está inscrito en el torneo «Copa», que aún no inicia: debe seguir cumpliendo los requisitos " +
      'para participar y con este cambio no los cumpliría. El equipo tiene 7 integrantes; se requieren al menos 8.'
    serve(makeTeam({ locked: false }), me(CAPTAIN_ID, 4))
    const served = vi.mocked(globalThis.fetch).getMockImplementation()!
    vi.mocked(globalThis.fetch).mockImplementation(async (input, init) => {
      if (init?.method === 'DELETE' && String(input).endsWith('/teams/4/members/2')) {
        return jsonResponse({ status: 409, message: refusal }, 409)
      }
      return served(input, init)
    })
    renderMyTeam()

    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Retirar' }, { timeout: 3000 }))
    const dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getAllByRole('button').at(-1)!)

    // The page reports it like any other refusal: an error toast with the server's message.
    await waitFor(
      () => expect(useUiStore.getState().toasts).toContainEqual(expect.objectContaining({ kind: 'error', message: refusal })),
      { timeout: 3000 },
    )
    expect(screen.getByText('Pedro Gómez')).toBeInTheDocument()
    useUiStore.getState().clearToasts()
  })
})

describe('team size limits', () => {
  it('states 8 to 13 members from a single source', () => {
    expect([MIN_TEAM_MEMBERS, MAX_TEAM_MEMBERS]).toEqual([8, 13])
    expect(TEAM_SIZE_RULE).toBe('Entre 8 y 13 integrantes.')
    expect(rosterCount(9)).toBe('9 / 13 integrantes')
    expect(rosterCount(9, false)).toBe('9 / 13')
  })
})
