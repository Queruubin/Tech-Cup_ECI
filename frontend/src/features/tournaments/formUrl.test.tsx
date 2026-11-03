import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth.store'
import type { RegistrationResponse, TeamResponse, TournamentResponse, TournamentStatus, UserResponse } from '@/types/api'
import { CaptainRegistration } from './components/CaptainRegistration'
import { OrganizerPanel, type TournamentUpdate } from './components/OrganizerPanel'
import { FORM_URL_INVALID_MESSAGE, formUrlError, safeFormUrl } from './formUrl'
import { TournamentDetailPage } from './pages/TournamentDetailPage'

/** The link to the university form: managed by staff only, shown to captains only when it is a safe http(s) URL. */

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const FORM = 'https://forms.office.com/r/AbC123'

function tournament(overrides: Partial<TournamentResponse> = {}): TournamentResponse {
  return {
    id: 1,
    name: 'TechCup 2026-2',
    startDate: '2026-12-01',
    endDate: '2026-12-20',
    registrationDeadline: '2026-11-25',
    maxTeams: 8,
    fee: 50000,
    status: 'ACTIVE',
    rulebookFileId: 'rulebook',
    venues: [],
    approvedTeams: 1,
    suspendedFromStatus: null,
    formUrl: null,
    ...overrides,
  }
}

const TEAM: TeamResponse = {
  id: 4,
  name: 'Leones FC',
  colors: 'Rojo',
  status: 'ACTIVE',
  captain: { id: 1, fullName: 'Ana Pérez' },
  members: [],
  memberCount: 9,
  locked: false,
  photoFileId: null,
}

function user(roles: UserResponse['roles']): UserResponse {
  return {
    id: 1,
    fullName: 'Ana Pérez',
    email: 'ana@escuelaing.edu.co',
    phoneNumber: '+573001234567',
    schoolRelation: 'STUDENT',
    academicProgram: 'SYSTEMS_ENGINEERING',
    semester: 5,
    status: 'ACTIVE',
    birthDate: null,
    documentType: null,
    documentNumber: null,
    roles,
    hasProfile: true,
    teamId: 4,
  }
}

interface Call {
  method: string
  path: string
  body: unknown
}

function mockApi(handler: (method: string, path: string, body: unknown) => Response | undefined) {
  const calls: Call[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = new URL(String(input), 'http://localhost').pathname.replace(/^\/api/, '')
    const method = (init?.method ?? 'GET').toUpperCase()
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body
    calls.push({ method, path, body })
    return handler(method, path, body) ?? jsonResponse([])
  })
  return calls
}

vi.setConfig({ testTimeout: 15_000 })

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

describe('formUrl rule', () => {
  it('accepts absolute http and https URLs only', () => {
    for (const ok of [FORM, 'http://escuelaing.edu.co/formulario', 'HTTPS://Example.com/a?b=1#c', '  https://example.com/f  ']) {
      expect(formUrlError(ok)).toBeNull()
    }
    for (const bad of [
      'javascript:alert(1)',
      'data:text/html,<b>x</b>',
      'ftp://example.com/f',
      '/relative/form',
      'example.com/form',
      'http:example.com',
      'https:///path',
      'https://exa mple.com',
    ]) {
      expect(formUrlError(bad)).toBe(FORM_URL_INVALID_MESSAGE)
    }
    expect(formUrlError('   ')).toBe('Ingrese la URL del formulario de la Escuela.')
    expect(formUrlError(`https://example.com/${'a'.repeat(500)}`)).toBe('La URL del formulario no puede superar los 500 caracteres.')
    expect(safeFormUrl(' https://example.com/f ')).toBe('https://example.com/f')
    expect(safeFormUrl('javascript:alert(1)')).toBeNull()
    expect(safeFormUrl(null)).toBeNull()
  })
})

/** Mirrors the detail page: the panel's updates replace the tournament it renders. */
function StatefulPanel({ initial }: { initial: TournamentResponse }) {
  const [value, setValue] = useState(initial)
  return (
    <OrganizerPanel
      tournament={value}
      onUpdated={(update: TournamentUpdate) => setValue((previous) => (typeof update === 'function' ? update(previous) : update))}
    />
  )
}

describe('staff "Formulario de la Escuela" card', () => {
  it('validates on the client, saves the URL and then removes it behind a confirmation', async () => {
    let current: string | null = null
    const calls = mockApi((method, path, body) => {
      if (method === 'PUT' && path === '/tournaments/1/form-url') {
        current = (body as { url: string }).url
        return jsonResponse(tournament({ formUrl: current }))
      }
      if (method === 'DELETE' && path === '/tournaments/1/form-url') {
        current = null
        return jsonResponse(tournament())
      }
      return undefined
    })
    const actor = userEvent.setup()
    render(
      <MemoryRouter>
        <StatefulPanel initial={tournament()} />
      </MemoryRouter>,
    )

    expect(screen.getByText('Sin URL del formulario')).toBeInTheDocument()
    const input = screen.getByLabelText(/URL del formulario/)
    await actor.type(input, 'javascript:alert(1)')
    await actor.click(screen.getByRole('button', { name: 'Guardar URL' }))
    expect(screen.getByText(FORM_URL_INVALID_MESSAGE)).toBeInTheDocument()
    expect(calls.some((call) => call.method === 'PUT')).toBe(false)

    await actor.clear(input)
    await actor.type(input, `  ${FORM}  `)
    await actor.click(screen.getByRole('button', { name: 'Guardar URL' }))
    await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true))
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ url: FORM })
    const link = await screen.findByRole('link', { name: FORM })
    expect(link).toHaveAttribute('href', FORM)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')

    await actor.click(screen.getByRole('button', { name: 'Quitar URL' }))
    await actor.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Quitar' }))
    await waitFor(() =>
      expect(calls.some((call) => call.method === 'DELETE' && call.path === '/tournaments/1/form-url')).toBe(true),
    )
    expect(await screen.findByText('Sin URL del formulario')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Quitar URL' })).not.toBeInTheDocument()
  })

  it('shows the server message when it refuses the URL', async () => {
    mockApi((method, path) =>
      method === 'PUT' && path === '/tournaments/1/form-url'
        ? jsonResponse({ status: 409, message: 'No se puede cambiar la URL del formulario de un torneo finalizado.' }, 409)
        : undefined,
    )
    const actor = userEvent.setup()
    render(
      <MemoryRouter>
        <StatefulPanel initial={tournament()} />
      </MemoryRouter>,
    )

    await actor.type(screen.getByLabelText(/URL del formulario/), FORM)
    await actor.click(screen.getByRole('button', { name: 'Guardar URL' }))
    expect(await screen.findByText('No se puede cambiar la URL del formulario de un torneo finalizado.')).toBeInTheDocument()
  })

  it.each<TournamentStatus>(['FINISHED', 'SUSPENDED'])('offers no edit controls while %s', (status) => {
    mockApi(() => undefined)
    render(
      <MemoryRouter>
        <StatefulPanel
          initial={tournament({ status, formUrl: FORM, suspendedFromStatus: status === 'SUSPENDED' ? 'ACTIVE' : null })}
        />
      </MemoryRouter>,
    )

    expect(screen.getByText('Formulario de la Escuela')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: FORM })).toBeInTheDocument()
    expect(screen.queryByLabelText(/URL del formulario/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Quitar URL' })).not.toBeInTheDocument()
  })

  it('never renders an unsafe stored value as a link', () => {
    mockApi(() => undefined)
    render(
      <MemoryRouter>
        <StatefulPanel initial={tournament({ formUrl: 'javascript:alert(1)' })} />
      </MemoryRouter>,
    )

    expect(screen.queryByRole('link', { name: /javascript/ })).not.toBeInTheDocument()
    expect(screen.getByText(/La URL guardada no es válida/)).toBeInTheDocument()
  })
})

function captainApi(mine: RegistrationResponse | null) {
  return mockApi((_method, path) => {
    if (path === '/teams/mine') return jsonResponse(TEAM)
    if (path === '/teams/4/eligibility') return jsonResponse({ eligible: true, problems: [] })
    if (path === '/tournaments/1/registrations/mine') {
      return mine ? jsonResponse(mine) : jsonResponse({ status: 404, message: 'Sin inscripción' }, 404)
    }
    return undefined
  })
}

function renderCaptain(value: TournamentResponse) {
  useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user: user(['PLAYER', 'CAPTAIN']) })
  return render(
    <MemoryRouter>
      <CaptainRegistration tournament={value} />
    </MemoryRouter>,
  )
}

describe('captain registration form link', () => {
  it('links to the form next to the evidence input, in a new tab', async () => {
    captainApi(null)
    renderCaptain(tournament({ formUrl: FORM }))

    const link = await screen.findByRole('link', { name: 'Llenar el formulario de la Escuela' }, { timeout: 3000 })
    expect(link).toHaveAttribute('href', FORM)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByLabelText(/Evidencia del formulario de la Escuela/)).toBeInTheDocument()
  })

  it('does not render a javascript: URL from the API as a link, and still lets the captain register', async () => {
    captainApi(null)
    renderCaptain(tournament({ formUrl: 'javascript:alert(document.cookie)' }))

    await screen.findByText('El equipo cumple los requisitos', undefined, { timeout: 3000 })
    await waitFor(() => expect(screen.queryByText('Cargando…')).not.toBeInTheDocument(), { timeout: 3000 })
    expect(screen.getByRole('button', { name: 'Enviar inscripción' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Llenar el formulario de la Escuela' })).not.toBeInTheDocument()
    expect(document.querySelector('a[href^="javascript"]')).toBeNull()
  })

  it('shows nothing extra when no URL is set', async () => {
    captainApi(null)
    renderCaptain(tournament())

    await screen.findByText('El equipo cumple los requisitos', undefined, { timeout: 3000 })
    await waitFor(() => expect(screen.queryByText('Cargando…')).not.toBeInTheDocument(), { timeout: 3000 })
    expect(screen.queryByRole('link', { name: 'Llenar el formulario de la Escuela' })).not.toBeInTheDocument()
  })

  it('repeats the link in the missing-evidence alert', async () => {
    captainApi({
      id: 9,
      tournamentId: 1,
      teamId: 4,
      teamName: 'Leones FC',
      receiptFileId: 'receipt-1',
      formEvidenceFileId: null,
      hasFormEvidence: false,
      status: 'UNDER_REVIEW',
      reviewNote: null,
      createdAt: '2026-11-01T10:00:00Z',
      reviewedAt: null,
    })
    renderCaptain(tournament({ formUrl: FORM }))

    const alert = (await screen.findByText('Subir evidencia del formulario', undefined, { timeout: 3000 })).closest(
      '[role="status"]',
    ) as HTMLElement
    const link = within(alert).getByRole('link', { name: 'Llenar el formulario de la Escuela' })
    expect(link).toHaveAttribute('href', FORM)
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })
})

describe('non-staff never see the edit controls', () => {
  it('a captain asking for the management tab gets the information tab, without the form-URL controls', async () => {
    useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user: user(['PLAYER', 'CAPTAIN']) })
    mockApi((_method, path) => (path === '/tournaments/1' ? jsonResponse(tournament({ formUrl: FORM })) : undefined))

    render(
      <MemoryRouter initialEntries={['/tournaments/1?tab=manage']}>
        <Routes>
          <Route path="/tournaments/:id" element={<TournamentDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByRole('tab', { name: 'Información' }, { timeout: 3000 })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('tab', { name: 'Gestión' })).not.toBeInTheDocument()
    expect(screen.queryByText('Formulario de la Escuela')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/URL del formulario/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Guardar URL|Cambiar URL|Quitar URL/ })).not.toBeInTheDocument()
  })
})
