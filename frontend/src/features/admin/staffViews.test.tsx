import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TeamDetailPage } from '@/features/teams/pages/TeamDetailPage'
import { useAuthStore } from '@/store/auth.store'
import type { PlayerProfileResponse, Role, TeamResponse, UserResponse } from '@/types/api'
import { UserDetailPage } from './pages/UserDetailPage'
import { REMOVE_PHOTO_QUESTION } from './photoModeration'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const team: TeamResponse = {
  id: 5,
  name: 'Leones FC',
  colors: 'Rojo',
  status: 'ACTIVE',
  captain: { id: 9, fullName: 'Ana Pérez' },
  members: [
    { userId: 9, fullName: 'Ana Pérez', position: 'FORWARD', jerseyNumber: 9, academicProgram: 'SYSTEMS_ENGINEERING', photoFileId: 'photo-9' },
    { userId: 12, fullName: 'Luis Gómez', position: 'DEFENDER', jerseyNumber: 4, academicProgram: 'AI_ENGINEERING', photoFileId: null },
  ],
  memberCount: 2,
  locked: true,
}

const luis: UserResponse = {
  id: 12,
  fullName: 'Luis Gómez',
  email: 'luis@mail.com',
  phoneNumber: null,
  schoolRelation: 'STUDENT',
  academicProgram: 'AI_ENGINEERING',
  semester: 3,
  status: 'ACTIVE',
  birthDate: '2005-02-03',
  documentType: 'TI',
  documentNumber: '99887766',
  roles: ['PLAYER'],
  hasProfile: true,
  teamId: 5,
}

const luisProfile: PlayerProfileResponse = {
  userId: 12,
  fullName: 'Luis Gómez',
  position: 'DEFENDER',
  jerseyNumber: 4,
  photoFileId: 'photo-12',
  teamId: 5,
  teamName: 'Leones FC',
  teamLocked: true,
}

function signIn(roles: Role[]) {
  useAuthStore.setState({
    token: 'jwt',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { ...luis, id: 1, fullName: 'Staff', roles, hasProfile: false, teamId: null },
  })
}

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    if (url.includes('/files/')) return new Response(new Blob(['x'], { type: 'image/png' }))
    if (url.endsWith('/eligibility')) return jsonResponse({ eligible: true, problems: [] })
    if (url.endsWith('/teams/5')) return jsonResponse(team)
    if (url.endsWith('/admin/users/12/photo') && init?.method === 'DELETE') return jsonResponse({ ...luisProfile, photoFileId: null })
    if (url.endsWith('/admin/users/9/photo') && init?.method === 'DELETE') return jsonResponse({ ...luisProfile, userId: 9, photoFileId: null })
    if (url.endsWith('/admin/users/12')) {
      const roles = useAuthStore.getState().user?.roles ?? []
      // An organizer gets the directory view: no birth date nor identity document.
      return jsonResponse(roles.includes('ADMIN') ? luis : { ...luis, birthDate: null, documentType: null, documentNumber: null })
    }
    if (url.endsWith('/players/12/profile')) return jsonResponse(luisProfile)
    return jsonResponse({})
  })
  if (!URL.createObjectURL) {
    Object.assign(URL, { createObjectURL: () => 'blob:photo', revokeObjectURL: () => undefined })
  }
})

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

function renderTeam() {
  render(
    <MemoryRouter initialEntries={['/teams/5']}>
      <Routes>
        <Route path="/teams/:id" element={<TeamDetailPage />} />
        <Route path="/users/:id" element={<UserDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('team roster for staff', () => {
  it('links every member to the user profile for an organizer, without photo moderation', async () => {
    signIn(['ORGANIZER'])
    renderTeam()

    const link = await screen.findByRole('link', { name: 'Luis Gómez' }, { timeout: 3000 })
    expect(link).toHaveAttribute('href', '/users/12')
    expect(screen.getByRole('link', { name: 'Ana Pérez' })).toHaveAttribute('href', '/users/9')
    expect(screen.queryByRole('button', { name: 'Quitar foto' })).not.toBeInTheDocument()
  })

  it('keeps the plain roster for a player', async () => {
    signIn(['PLAYER'])
    renderTeam()

    expect(await screen.findByRole('heading', { name: 'Leones FC' }, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByText('Luis Gómez')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Luis Gómez' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Quitar foto' })).not.toBeInTheDocument()
  })

  it('lets an admin remove a member photo after a confirmation', async () => {
    signIn(['ADMIN'])
    renderTeam()
    const user = userEvent.setup()

    // Only Ana has a photo.
    const buttons = await screen.findAllByRole('button', { name: 'Quitar foto' }, { timeout: 3000 })
    expect(buttons).toHaveLength(1)
    await user.click(buttons[0]!)
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent(REMOVE_PHOTO_QUESTION)
    await user.click(within(dialog).getByRole('button', { name: 'Quitar foto' }))

    await waitFor(() =>
      expect(
        vi
          .mocked(globalThis.fetch)
          .mock.calls.some(([input, init]) => String(input).endsWith('/admin/users/9/photo') && init?.method === 'DELETE'),
      ).toBe(true),
    )
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Quitar foto' })).not.toBeInTheDocument())
  })

  it('opens the user profile from the roster', async () => {
    signIn(['ADMIN'])
    renderTeam()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('link', { name: 'Luis Gómez' }, { timeout: 3000 }))

    expect(await screen.findByText('99887766', {}, { timeout: 3000 })).toBeInTheDocument()
  })
})

describe('UserDetailPage', () => {
  function renderUser() {
    render(
      <MemoryRouter initialEntries={['/users/12']}>
        <Routes>
          <Route path="/users/:id" element={<UserDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )
  }

  it('shows an admin every field, the sport profile and the team', async () => {
    signIn(['ADMIN'])
    renderUser()

    expect(await screen.findByRole('heading', { name: 'Luis Gómez' }, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByText('luis@mail.com')).toBeInTheDocument()
    expect(screen.getByText('99887766')).toBeInTheDocument()
    expect(screen.getByText('Tarjeta de identidad')).toBeInTheDocument()
    expect(screen.getByText('Ingeniería de Inteligencia Artificial')).toBeInTheDocument()
    expect(await screen.findByText('Defensa', {}, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Leones FC' })).toHaveAttribute('href', '/teams/5')
    expect(screen.getByRole('button', { name: 'Quitar foto' })).toBeInTheDocument()
  })

  it('hides the document and birth date from an organizer and offers no photo removal', async () => {
    signIn(['ORGANIZER'])
    renderUser()

    expect(await screen.findByText('luis@mail.com', {}, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.queryByText('99887766')).not.toBeInTheDocument()
    expect(screen.getAllByText('Solo visible para administradores')).toHaveLength(3)
    expect(await screen.findByText('Defensa', {}, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Quitar foto' })).not.toBeInTheDocument()
  })

  it('lets an admin remove the photo from the profile view', async () => {
    signIn(['ADMIN'])
    renderUser()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Quitar foto' }, { timeout: 3000 }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Quitar foto' }))

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Quitar foto' })).not.toBeInTheDocument())
  })
})
