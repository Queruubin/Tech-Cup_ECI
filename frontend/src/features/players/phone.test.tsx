import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppLayout } from '@/app/AppLayout'
import { RegisterForm } from '@/features/auth/components/RegisterForm'
import { EMPTY_REGISTER_VALUES, toRegisterRequest, validateRegister } from '@/features/auth/validation'
import { TeamDetailPage } from '@/features/teams/pages/TeamDetailPage'
import { formatPhone, normalizePhone, PHONE_ERROR, PHONE_HINT, validatePhone } from '@/lib/phone'
import { useAuthStore } from '@/store/auth.store'
import type { PlayerProfileResponse, Role, TeamResponse, UserResponse } from '@/types/api'
import { BasicInfoForm } from './components/BasicInfoForm'
import { MISSING_PHONE_TEXT, MISSING_PHONE_TITLE } from './components/MissingPhoneBanner'
import { ProfilePage } from './pages/ProfilePage'
import { needsPhoneBanner } from './phoneField'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function account(roles: Role[], phoneNumber: string | null = null, id = 10): UserResponse {
  return {
    id,
    fullName: 'Luis Gómez',
    email: 'luis@mail.com',
    phoneNumber,
    schoolRelation: 'STUDENT',
    academicProgram: 'SYSTEMS_ENGINEERING',
    semester: 4,
    status: 'ACTIVE',
    birthDate: '2004-01-01',
    documentType: 'CC',
    documentNumber: '1001',
    roles,
    hasProfile: true,
    teamId: 5,
  }
}

const PROFILE: PlayerProfileResponse = {
  userId: 10,
  fullName: 'Luis Gómez',
  position: 'FORWARD',
  jerseyNumber: 9,
  photoFileId: null,
  teamId: 5,
  teamName: 'Leones FC',
  teamLocked: true,
}

function signIn(user: UserResponse) {
  useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user })
}

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse({}))
})

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

describe('phone rule (mirrors the backend PhoneNumbers)', () => {
  it.each([
    ['3001234567', '+573001234567'],
    ['300 123 4567', '+573001234567'],
    ['300-123-4567', '+573001234567'],
    ['300.123.4567', '+573001234567'],
    ['(300) 123 4567', '+573001234567'],
    ['+57 300 123 4567', '+573001234567'],
    ['57 300 123 4567', '+573001234567'],
    ['  321 987 6543  ', '+573219876543'],
    ['+1 202 555 0143', '+12025550143'],
    ['+44 20 7946 0958', '+442079460958'],
    ['+49 30 1234', '+49301234'],
    ['+123456789012345', '+123456789012345'],
  ])('normalises %s to %s', (raw, expected) => {
    expect(validatePhone(raw)).toBeUndefined()
    expect(normalizePhone(raw)).toBe(expected)
  })

  it.each([
    '300123456',
    '30012345678',
    '2001234567',
    '6011234567',
    '+57 601 1234567',
    '+1234567',
    '+1234567890123456',
    '+0123456789',
    '300 ABC 4567',
    '300/123/4567',
    '++573001234567',
    '300 123 4567',
  ])('rejects %s with the backend message', (raw) => {
    expect(validatePhone(raw)).toBe(PHONE_ERROR)
    expect(normalizePhone(raw)).toBeNull()
  })

  it('treats blank input as no phone and formats stored Colombian mobiles', () => {
    expect(validatePhone('')).toBeUndefined()
    expect(validatePhone('   ')).toBeUndefined()
    expect(normalizePhone('  ')).toBeNull()
    expect(PHONE_ERROR).toBe(
      'Ingrese un celular válido: 10 dígitos que empiecen por 3 o un número internacional con +código de país.',
    )
    expect(formatPhone('+573001234567')).toBe('+57 300 123 4567')
    expect(formatPhone('+442079460958')).toBe('+442079460958')
    expect(formatPhone(null)).toBe('')
  })
})

describe('registration phone', () => {
  const valid = {
    ...EMPTY_REGISTER_VALUES,
    fullName: 'Ana Pérez',
    email: 'ana@mail.com',
    password: 'Clave1234',
    confirmPassword: 'Clave1234',
    academicProgram: 'SYSTEMS_ENGINEERING' as const,
    birthDate: '2003-04-10',
    documentType: 'CC' as const,
    documentNumber: '1001',
  }

  it('is optional, validated and sent normalised', () => {
    expect(validateRegister(valid)).toEqual({})
    expect(toRegisterRequest(valid).phoneNumber).toBeNull()
    expect(validateRegister({ ...valid, phoneNumber: '12345' }).phoneNumber).toBe(PHONE_ERROR)
    expect(toRegisterRequest({ ...valid, phoneNumber: '300 123 4567' }).phoneNumber).toBe('+573001234567')
  })

  it('shows the optional "Celular" field with its hint and the validation message', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(<RegisterForm loading={false} error={null} fieldErrors={{}} onSubmit={onSubmit} />)

    const phone = screen.getByLabelText('Celular')
    expect(phone).toHaveAttribute('type', 'tel')
    expect(phone).not.toBeRequired()
    expect(screen.getByText(PHONE_HINT)).toBeInTheDocument()

    await user.type(phone, '601 123 4567')
    await user.click(screen.getByRole('button', { name: 'Crear cuenta' }))
    expect(await screen.findByText(PHONE_ERROR)).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })
})

describe('BasicInfoForm phone', () => {
  it('sends a typed phone normalised', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(<BasicInfoForm user={account(['PLAYER'])} loading={false} error={null} fieldErrors={{}} canEditRelation={false} onSubmit={onSubmit} />)

    await user.type(screen.getByLabelText('Celular'), '+57 300-123-4567')
    await user.click(screen.getByRole('button', { name: 'Actualizar información' }))
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ phoneNumber: '+573001234567' }))
  })

  it('removes a stored phone when the field is cleared and refuses an invalid one', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(
      <BasicInfoForm
        user={account(['PLAYER'], '+573001234567')}
        loading={false}
        error={null}
        fieldErrors={{}}
        canEditRelation
        submitLabel="Guardar cambios"
        onSubmit={onSubmit}
      />,
    )

    const phone = screen.getByLabelText('Celular')
    expect(phone).toHaveValue('+57 300 123 4567')
    await user.clear(phone)
    await user.type(phone, '12345')
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(screen.getByText(PHONE_ERROR)).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()

    await user.clear(phone)
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ phoneNumber: '' }))
  })
})

describe('captain phone banner', () => {
  function renderShell(path = '/') {
    return render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/" element={<p>Inicio</p>} />
            <Route path="/profile" element={<ProfilePage />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
  }

  it('is shown only to a user who literally holds CAPTAIN and has no phone', () => {
    expect(needsPhoneBanner(account(['PLAYER', 'CAPTAIN']))).toBe(true)
    expect(needsPhoneBanner(account(['PLAYER', 'CAPTAIN'], '+573001234567'))).toBe(false)
    expect(needsPhoneBanner(account(['PLAYER']))).toBe(false)
    expect(needsPhoneBanner(account(['ADMIN']))).toBe(false)
    expect(needsPhoneBanner(null)).toBe(false)
  })

  it('warns a captain without phone on every page with a link to the profile field', () => {
    signIn(account(['PLAYER', 'CAPTAIN']))
    renderShell()

    const banner = screen.getByText(MISSING_PHONE_TITLE).closest('[role="status"]') as HTMLElement
    expect(banner).toBeInTheDocument()
    expect(within(banner).getByText(MISSING_PHONE_TEXT)).toBeInTheDocument()
    expect(within(banner).getByRole('link', { name: 'Agregar mi celular' })).toHaveAttribute('href', '/profile#celular')
    expect(within(banner).queryByRole('button', { name: 'Cerrar' })).not.toBeInTheDocument()
  })

  it.each([
    ['a captain with a phone', account(['PLAYER', 'CAPTAIN'], '+573001234567')],
    ['a plain player', account(['PLAYER'])],
    ['an organizer', account(['ORGANIZER'])],
    ['an admin who is not a captain', account(['ADMIN'])],
  ])('is hidden for %s', (_label, user) => {
    signIn(user)
    renderShell()

    expect(screen.getByText('Inicio', { selector: 'p' })).toBeInTheDocument()
    expect(screen.queryByText(MISSING_PHONE_TITLE)).not.toBeInTheDocument()
  })

  it('focuses the phone field and disappears once the phone is saved, without a reload', async () => {
    const captain = account(['PLAYER', 'CAPTAIN'])
    const saved = { ...captain, phoneNumber: '+573001234567' }
    let stored: UserResponse = captain
    const fetchMock = vi.mocked(globalThis.fetch)
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      if (url.endsWith('/users/10') && method === 'PATCH') {
        stored = saved
        return jsonResponse(saved)
      }
      if (url.endsWith('/auth/me')) return jsonResponse(stored)
      if (url.endsWith('/players/me/profile')) return jsonResponse(PROFILE)
      return jsonResponse({})
    })
    signIn(captain)
    const user = userEvent.setup()
    renderShell('/profile#celular')

    expect(screen.getByText(MISSING_PHONE_TITLE)).toBeInTheDocument()
    const phone = screen.getByLabelText('Celular')
    await waitFor(() => expect(phone).toHaveFocus())

    await user.type(phone, '300 123 4567')
    await user.click(screen.getByRole('button', { name: 'Actualizar información' }))

    await waitFor(() => expect(screen.queryByText(MISSING_PHONE_TITLE)).not.toBeInTheDocument())
    expect(useAuthStore.getState().user?.phoneNumber).toBe('+573001234567')
    const patch = fetchMock.mock.calls.find(([, init]) => init?.method === 'PATCH')
    expect(JSON.parse(String(patch?.[1]?.body))).toMatchObject({ phoneNumber: '+573001234567' })
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/auth/me'))).toBe(true)
  })
})

describe('captain phone on the team page', () => {
  const team: TeamResponse = {
    id: 5,
    name: 'Leones FC',
    colors: 'Rojo',
    status: 'ACTIVE',
    captain: { id: 7, fullName: 'Ana Pérez' },
    members: [{ userId: 7, fullName: 'Ana Pérez', position: 'FORWARD', jerseyNumber: 9, academicProgram: 'OTHER', photoFileId: null }],
    memberCount: 1,
    locked: false,
    photoFileId: null,
  }

  function renderTeam() {
    const fetchMock = vi.mocked(globalThis.fetch)
    fetchMock.mockImplementation(async (input) => {
      const url = String(input)
      if (url.endsWith('/teams/5')) return jsonResponse(team)
      if (url.endsWith('/teams/5/eligibility')) return jsonResponse({ eligible: false, problems: [] })
      if (url.endsWith('/admin/users/7')) return jsonResponse(account(['PLAYER', 'CAPTAIN'], '+573001234567', 7))
      return jsonResponse({})
    })
    render(
      <MemoryRouter initialEntries={['/teams/5']}>
        <Routes>
          <Route path="/teams/:id" element={<TeamDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )
    return fetchMock
  }

  it('shows staff the captain phone from the staff user view', async () => {
    signIn(account(['ORGANIZER'], null, 2))
    renderTeam()

    const link = await screen.findByRole('link', { name: '+57 300 123 4567' })
    expect(link).toHaveAttribute('href', 'tel:+573001234567')
    expect(screen.getByText(/Celular del capitán/)).toBeInTheDocument()
  })

  it('never asks for it for a plain player', async () => {
    signIn(account(['PLAYER'], null, 3))
    const fetchMock = renderTeam()

    expect(await screen.findByRole('heading', { name: 'Leones FC' })).toBeInTheDocument()
    expect(screen.queryByText(/Celular del capitán/)).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/admin/users'))).toBe(false)
  })
})
