import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth.store'
import type { Role, TeamResponse, UserResponse } from '@/types/api'
import { TeamCard } from './components/TeamCard'
import { TeamDetailPage } from './pages/TeamDetailPage'
import { canManageTeamPhoto } from './teamPhoto'

/** The optional team photo: who may change it (captain, organizer, admin) and the upload/removal flow. */

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
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
    members: [],
    memberCount: 9,
    locked: false,
    photoFileId: null,
    ...overrides,
  }
}

function me(id: number, roles: Role[]): UserResponse {
  return {
    id,
    fullName: 'Usuario',
    email: 'u@escuelaing.edu.co',
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
    teamId: id === CAPTAIN_ID || id === MEMBER_ID ? 4 : null,
  }
}

interface Call {
  method: string
  path: string
  body: unknown
}

/** Serves the team page; the photo endpoints answer with the updated team. */
function serve(team: TeamResponse) {
  const calls: Call[] = []
  let current = team
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = new URL(String(input), 'http://localhost').pathname.replace(/^\/api/, '')
    const method = (init?.method ?? 'GET').toUpperCase()
    calls.push({ method, path, body: init?.body })
    if (path === '/teams/4/photo' && method === 'PUT') {
      current = { ...current, photoFileId: 'new-photo' }
      return jsonResponse(current)
    }
    if (path === '/teams/4/photo' && method === 'DELETE') {
      current = { ...current, photoFileId: null }
      return jsonResponse(current)
    }
    if (path === '/teams/4') return jsonResponse(current)
    if (path.endsWith('/eligibility')) return jsonResponse({ eligible: true, problems: [] })
    if (path.startsWith('/files/')) return new Response(new Blob(['x'], { type: 'image/png' }), { status: 200 })
    return jsonResponse([])
  })
  return calls
}

function signIn(user: UserResponse) {
  useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user })
}

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/teams/4']}>
      <Routes>
        <Route path="/teams/:id" element={<TeamDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

vi.setConfig({ testTimeout: 15_000 })

beforeEach(() => {
  if (!URL.createObjectURL) {
    Object.assign(URL, { createObjectURL: () => 'blob:photo', revokeObjectURL: () => undefined })
  }
})

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

describe('canManageTeamPhoto', () => {
  const team = makeTeam()

  it('lets the team captain, an organizer and an admin manage the photo', () => {
    expect(canManageTeamPhoto({ id: CAPTAIN_ID, roles: ['PLAYER', 'CAPTAIN'] }, team)).toBe(true)
    expect(canManageTeamPhoto({ id: 50, roles: ['ORGANIZER'] }, team)).toBe(true)
    expect(canManageTeamPhoto({ id: 60, roles: ['ADMIN'] }, team)).toBe(true)
  })

  it('refuses other members, the captain of another team and anonymous viewers', () => {
    expect(canManageTeamPhoto({ id: MEMBER_ID, roles: ['PLAYER'] }, team)).toBe(false)
    expect(canManageTeamPhoto({ id: 7, roles: ['PLAYER', 'CAPTAIN'] }, team)).toBe(false)
    expect(canManageTeamPhoto({ id: 8, roles: ['REFEREE'] }, team)).toBe(false)
    expect(canManageTeamPhoto(null, team)).toBe(false)
  })

  it('ignores the tournament lock: the photo can change while the roster is frozen', () => {
    expect(canManageTeamPhoto({ id: CAPTAIN_ID, roles: ['PLAYER', 'CAPTAIN'] }, makeTeam({ locked: true }))).toBe(true)
  })
})

describe('team photo controls on the team page', () => {
  it.each([
    ['the captain', me(CAPTAIN_ID, ['PLAYER', 'CAPTAIN'])],
    ['an organizer', me(50, ['ORGANIZER'])],
    ['an admin', me(60, ['ADMIN'])],
  ])('shows "Agregar foto" to %s, also while the team is locked', async (_label, viewer) => {
    signIn(viewer)
    serve(makeTeam({ locked: true }))
    renderDetail()

    expect(await screen.findByRole('button', { name: 'Agregar foto' }, { timeout: 3000 })).toBeEnabled()
    // Without a photo the header shows the team's initials.
    expect(screen.getByRole('img', { name: 'Iniciales de Leones FC' })).toHaveTextContent('LF')
  })

  it.each([
    ['another member', me(MEMBER_ID, ['PLAYER'])],
    ['a player of no team', me(30, ['PLAYER'])],
  ])('hides the controls from %s', async (_label, viewer) => {
    signIn(viewer)
    serve(makeTeam({ photoFileId: 'photo-1' }))
    renderDetail()

    expect(await screen.findByRole('heading', { name: 'Leones FC' }, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cambiar foto' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Agregar foto' })).not.toBeInTheDocument()
  })

  it('uploads a new photo as multipart "file" with a preview first', async () => {
    signIn(me(CAPTAIN_ID, ['PLAYER', 'CAPTAIN']))
    const calls = serve(makeTeam())
    renderDetail()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Agregar foto' }, { timeout: 3000 }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('button', { name: 'Guardar foto' })).toBeDisabled()
    await user.upload(within(dialog).getByLabelText('Elegir archivo'), new File(['png'], 'team.png', { type: 'image/png' }))
    expect(within(dialog).getByAltText('Vista previa de la nueva foto')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Guardar foto' }))

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT' && call.path === '/teams/4/photo')).toBe(true))
    const body = calls.find((call) => call.method === 'PUT' && call.path === '/teams/4/photo')?.body
    expect(body).toBeInstanceOf(FormData)
    expect((body as FormData).get('file')).toBeInstanceOf(File)
    expect(await screen.findByRole('button', { name: 'Cambiar foto' })).toBeInTheDocument()
  })

  it('removes the photo only after confirming', async () => {
    signIn(me(60, ['ADMIN']))
    const calls = serve(makeTeam({ photoFileId: 'photo-1' }))
    renderDetail()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Cambiar foto' }, { timeout: 3000 }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Quitar foto' }))
    const confirm = screen.getByRole('dialog')
    expect(confirm).toHaveTextContent('Quitar la foto de Leones FC')
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false)
    await user.click(within(confirm).getByRole('button', { name: 'Quitar foto' }))

    await waitFor(() => expect(calls.some((call) => call.method === 'DELETE' && call.path === '/teams/4/photo')).toBe(true))
    expect(await screen.findByRole('button', { name: 'Agregar foto' })).toBeInTheDocument()
  })
})

describe('TeamCard', () => {
  it('shows the team photo, or the initials without one', async () => {
    serve(makeTeam())
    const { rerender } = render(
      <MemoryRouter>
        <TeamCard team={makeTeam()} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('img', { name: 'Iniciales de Leones FC' })).toBeInTheDocument()

    rerender(
      <MemoryRouter>
        <TeamCard team={makeTeam({ photoFileId: 'photo-1' })} />
      </MemoryRouter>,
    )
    expect(await screen.findByRole('img', { name: 'Foto de Leones FC' })).toBeInTheDocument()
  })
})
