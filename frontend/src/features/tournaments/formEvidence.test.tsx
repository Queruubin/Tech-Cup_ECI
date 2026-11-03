import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth.store'
import type { RegistrationResponse, TeamResponse, TournamentResponse, UserResponse } from '@/types/api'
import { CaptainRegistration } from './components/CaptainRegistration'
import { RegistrationsReview } from './components/RegistrationsReview'
import { APPROVAL_NEEDS_FORM_EVIDENCE, approvalBlocker } from './registrationEvidence'

/** Registration requires the receipt and the university-form evidence; approval waits for the evidence. */

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const TOURNAMENT: TournamentResponse = {
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

const CAPTAIN: UserResponse = {
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
  roles: ['PLAYER', 'CAPTAIN'],
  hasProfile: true,
  teamId: 4,
}

function registration(overrides: Partial<RegistrationResponse> = {}): RegistrationResponse {
  return {
    id: 9,
    tournamentId: 1,
    teamId: 4,
    teamName: 'Leones FC',
    receiptFileId: 'receipt-1',
    formEvidenceFileId: 'evidence-1',
    hasFormEvidence: true,
    status: 'UNDER_REVIEW',
    reviewNote: null,
    createdAt: '2026-11-01T10:00:00Z',
    reviewedAt: null,
    ...overrides,
  }
}

interface Call {
  method: string
  path: string
  body: unknown
}

function mockApi(handler: (method: string, path: string) => Response | undefined) {
  const calls: Call[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = new URL(String(input), 'http://localhost').pathname.replace(/^\/api/, '')
    const method = (init?.method ?? 'GET').toUpperCase()
    calls.push({ method, path, body: init?.body })
    return handler(method, path) ?? jsonResponse([])
  })
  return calls
}

const pdf = (name: string) => new File(['%PDF-1.4'], name, { type: 'application/pdf' })

vi.setConfig({ testTimeout: 15_000 })

beforeEach(() => {
  useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user: CAPTAIN })
})

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

function renderCaptain() {
  return render(
    <MemoryRouter>
      <CaptainRegistration tournament={TOURNAMENT} />
    </MemoryRouter>,
  )
}

describe('captain registration form', () => {
  it('requires both the payment receipt and the university-form evidence before sending', async () => {
    let mine: RegistrationResponse | null = null
    const calls = mockApi((method, path) => {
      if (path === '/teams/mine') return jsonResponse(TEAM)
      if (path === '/teams/4/eligibility') return jsonResponse({ eligible: true, problems: [] })
      if (path === '/tournaments/1/registrations/mine') {
        return mine ? jsonResponse(mine) : jsonResponse({ status: 404, message: 'Sin inscripción' }, 404)
      }
      if (method === 'POST' && path === '/tournaments/1/registrations') {
        mine = registration()
        return jsonResponse(mine, 201)
      }
      return undefined
    })
    renderCaptain()
    const user = userEvent.setup()

    // The registration query only starts once the team is known: wait until every card loaded.
    await screen.findByText('El equipo cumple los requisitos', undefined, { timeout: 3000 })
    await waitFor(() => expect(screen.queryByText('Cargando…')).not.toBeInTheDocument(), { timeout: 3000 })
    const send = screen.getByRole('button', { name: 'Enviar inscripción' })
    expect(send).toBeDisabled()
    expect(screen.getByText(/Adjunte el comprobante de pago y la evidencia del formulario de la Escuela/)).toBeInTheDocument()

    await user.upload(screen.getByLabelText(/Comprobante de pago/), pdf('pago.pdf'))
    expect(send).toBeDisabled()
    await user.upload(screen.getByLabelText(/Evidencia del formulario de la Escuela/), pdf('formulario.pdf'))
    expect(send).toBeEnabled()
    await user.click(send)

    await waitFor(() => expect(calls.some((call) => call.method === 'POST' && call.path === '/tournaments/1/registrations')).toBe(true))
    const body = calls.find((call) => call.method === 'POST' && call.path === '/tournaments/1/registrations')?.body as FormData
    expect((body.get('file') as File).name).toBe('pago.pdf')
    expect((body.get('formEvidence') as File).name).toBe('formulario.pdf')
    expect(await screen.findByRole('button', { name: 'Ver formulario' })).toBeInTheDocument()
  })

  it('asks the captain to upload the missing evidence of an old registration under review', async () => {
    let mine = registration({ formEvidenceFileId: null, hasFormEvidence: false })
    const calls = mockApi((method, path) => {
      if (path === '/teams/mine') return jsonResponse(TEAM)
      if (path === '/teams/4/eligibility') return jsonResponse({ eligible: true, problems: [] })
      if (path === '/tournaments/1/registrations/mine') return jsonResponse(mine)
      if (method === 'PUT' && path === '/registrations/9/form-evidence') {
        mine = registration({ formEvidenceFileId: 'evidence-2' })
        return jsonResponse(mine)
      }
      return undefined
    })
    renderCaptain()
    const user = userEvent.setup()

    const alert = await screen.findByText('Subir evidencia del formulario', undefined, { timeout: 3000 })
    const box = alert.closest('[role="alert"], [role="status"]') ?? alert.parentElement!.parentElement!
    expect(screen.queryByRole('button', { name: 'Ver formulario' })).not.toBeInTheDocument()
    const upload = within(box as HTMLElement).getByRole('button', { name: 'Subir evidencia' })
    expect(upload).toBeDisabled()
    await user.upload(within(box as HTMLElement).getByLabelText('Elegir archivo'), pdf('formulario.pdf'))
    await user.click(upload)

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT' && call.path === '/registrations/9/form-evidence')).toBe(true))
    const body = calls.find((call) => call.method === 'PUT')?.body as FormData
    expect((body.get('file') as File).name).toBe('formulario.pdf')
    expect(await screen.findByRole('button', { name: 'Ver formulario' })).toBeInTheDocument()
    expect(screen.queryByText('Subir evidencia del formulario')).not.toBeInTheDocument()
  })

  it('does not ask for evidence once the registration was approved without it', async () => {
    mockApi((_method, path) => {
      if (path === '/teams/mine') return jsonResponse(TEAM)
      if (path === '/teams/4/eligibility') return jsonResponse({ eligible: true, problems: [] })
      if (path === '/tournaments/1/registrations/mine') {
        return jsonResponse(registration({ status: 'APPROVED', formEvidenceFileId: null, hasFormEvidence: false }))
      }
      return undefined
    })
    renderCaptain()

    expect(await screen.findByText(/Su equipo participa en el torneo/, undefined, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.queryByText('Subir evidencia del formulario')).not.toBeInTheDocument()
  })
})

describe('organizer review', () => {
  it('offers both documents and disables "Aprobar" while the evidence is missing', async () => {
    mockApi((_method, path) =>
      path === '/tournaments/1/registrations'
        ? jsonResponse([
            registration({ id: 9, teamId: 4, teamName: 'Leones FC' }),
            registration({ id: 10, teamId: 5, teamName: 'Tigres', formEvidenceFileId: null, hasFormEvidence: false }),
          ])
        : undefined,
    )
    render(
      <MemoryRouter>
        <RegistrationsReview tournamentId={1} tournamentStatus="ACTIVE" />
      </MemoryRouter>,
    )

    const complete = (await screen.findByRole('link', { name: 'Leones FC' }, { timeout: 3000 })).closest('li') as HTMLElement
    expect(within(complete).getByRole('button', { name: 'Ver comprobante' })).toBeInTheDocument()
    expect(within(complete).getByRole('button', { name: 'Ver formulario' })).toBeInTheDocument()
    expect(within(complete).getByRole('button', { name: 'Aprobar' })).toBeEnabled()

    const legacy = screen.getByRole('link', { name: 'Tigres' }).closest('li') as HTMLElement
    expect(within(legacy).getByRole('button', { name: 'Ver comprobante' })).toBeInTheDocument()
    expect(within(legacy).queryByRole('button', { name: 'Ver formulario' })).not.toBeInTheDocument()
    expect(within(legacy).getByText('Sin evidencia del formulario')).toBeInTheDocument()
    const approve = within(legacy).getByRole('button', { name: 'Aprobar' })
    expect(approve).toBeDisabled()
    expect(approve).toHaveAttribute('title', APPROVAL_NEEDS_FORM_EVIDENCE)
    expect(within(legacy).getByText(APPROVAL_NEEDS_FORM_EVIDENCE)).toBeInTheDocument()
    // Rejecting stays possible.
    expect(within(legacy).getByRole('button', { name: 'Rechazar' })).toBeEnabled()
  })

  it('blocks only registrations under review', () => {
    expect(approvalBlocker({ status: 'UNDER_REVIEW', hasFormEvidence: false })).toBe(APPROVAL_NEEDS_FORM_EVIDENCE)
    expect(approvalBlocker({ status: 'UNDER_REVIEW', hasFormEvidence: true })).toBeNull()
    expect(approvalBlocker({ status: 'APPROVED', hasFormEvidence: false })).toBeNull()
  })
})
