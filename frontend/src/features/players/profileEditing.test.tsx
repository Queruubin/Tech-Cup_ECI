import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth.store'
import type { PlayerProfileResponse, UserResponse } from '@/types/api'
import { JERSEY_LOCKED_HINT } from './components/ProfileForm'
import { ProfilePage } from './pages/ProfilePage'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const me: UserResponse = {
  id: 10,
  fullName: 'Luis Gómez',
  email: 'luis@mail.com',
  phoneNumber: null,
  schoolRelation: 'STUDENT',
  academicProgram: 'SYSTEMS_ENGINEERING',
  semester: 4,
  status: 'ACTIVE',
  birthDate: '2004-01-01',
  documentType: 'CC',
  documentNumber: '1001',
  roles: ['PLAYER'],
  hasProfile: true,
  teamId: 5,
}

function profile(overrides: Partial<PlayerProfileResponse> = {}): PlayerProfileResponse {
  return {
    userId: 10,
    fullName: 'Luis Gómez',
    position: 'FORWARD',
    jerseyNumber: 9,
    photoFileId: null,
    teamId: 5,
    teamName: 'Leones FC',
    teamLocked: false,
    ...overrides,
  }
}

function renderProfile(current: PlayerProfileResponse) {
  const fetchMock = vi.mocked(globalThis.fetch)
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (url.endsWith('/players/me/profile/photo') && method === 'DELETE') {
      return jsonResponse({ ...current, photoFileId: null })
    }
    if (url.endsWith('/players/me/profile')) return jsonResponse(current)
    if (url.endsWith('/auth/me')) return jsonResponse(me)
    if (url.includes('/files/')) return new Response(new Blob(['x'], { type: 'image/png' }))
    return jsonResponse({})
  })
  render(
    <MemoryRouter>
      <ProfilePage />
    </MemoryRouter>,
  )
  return fetchMock
}

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse({}))
  useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user: me })
  if (!URL.createObjectURL) {
    Object.assign(URL, { createObjectURL: () => 'blob:photo', revokeObjectURL: () => undefined })
  }
})

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

describe('ProfilePage while in a team', () => {
  it('keeps the photo and the position editable and only freezes the jersey while the team plays a tournament', async () => {
    renderProfile(profile({ teamLocked: true }))

    const jersey = await screen.findByLabelText(/Número de dorsal/, {}, { timeout: 3000 })
    expect(jersey).toBeDisabled()
    expect(screen.getByText(JERSEY_LOCKED_HINT)).toBeInTheDocument()
    expect(screen.getByLabelText(/Posición/)).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeEnabled()
    expect(screen.getByLabelText('Elegir archivo')).toBeEnabled()
    // The old blanket notice is gone.
    expect(screen.queryByText(/no se puede modificar mientras pertenece a un equipo/)).not.toBeInTheDocument()
  })

  it('lets a member of a team that is not playing change the jersey too', async () => {
    renderProfile(profile({ teamLocked: false }))

    const jersey = await screen.findByLabelText(/Número de dorsal/, {}, { timeout: 3000 })
    expect(jersey).toBeEnabled()
    expect(screen.queryByText(JERSEY_LOCKED_HINT)).not.toBeInTheDocument()
  })

  it('sends the unchanged jersey with a new position while the jersey is frozen', async () => {
    const fetchMock = renderProfile(profile({ teamLocked: true }))
    const user = userEvent.setup()

    await user.selectOptions(await screen.findByLabelText(/Posición/, {}, { timeout: 3000 }), 'GOALKEEPER')
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    await waitFor(() => {
      const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')
      expect(put).toBeDefined()
      expect(JSON.parse(String(put?.[1]?.body))).toEqual({ position: 'GOALKEEPER', jerseyNumber: 9 })
    })
  })

  it('removes the own photo after a confirmation', async () => {
    const fetchMock = renderProfile(profile({ teamLocked: true, photoFileId: 'photo-1' }))
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Quitar foto' }, { timeout: 3000 }))
    const dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Quitar foto' }))

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) => String(input).endsWith('/players/me/profile/photo') && init?.method === 'DELETE',
        ),
      ).toBe(true),
    )
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Quitar foto' })).not.toBeInTheDocument())
  })
})
