import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { filenameFromContentDisposition } from '@/lib/api'
import { useAuthStore } from '@/store/auth.store'
import type { TournamentResponse, TournamentStatus, VenueResponse } from '@/types/api'
import { OrganizerPanel } from './components/OrganizerPanel'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const venue: VenueResponse = { id: 3, name: 'Cancha norte', description: 'Sintética', imageFileId: 'venue-old' }

function tournament(status: TournamentStatus): TournamentResponse {
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
    venues: [venue],
    approvedTeams: 2,
    suspendedFromStatus: status === 'SUSPENDED' ? 'ACTIVE' : null,
    formUrl: null,
  }
}

const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

beforeEach(() => {
  useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user: null })
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    if (url.endsWith('/players/export')) {
      return new Response(new Blob(['PK'], { type: XLSX }), {
        status: 200,
        headers: { 'Content-Type': XLSX, 'Content-Disposition': 'attachment; filename="jugadores-techcup-2026-2.xlsx"' },
      })
    }
    if (url.endsWith('/venues/3/image') && init?.method === 'PUT') return jsonResponse({ ...venue, imageFileId: 'venue-new' })
    if (url.endsWith('/venues/3/image') && init?.method === 'DELETE') return jsonResponse({ ...venue, imageFileId: null })
    if (url.includes('/files/')) return new Response(new Blob(['x'], { type: 'image/png' }))
    return jsonResponse([])
  })
  Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:file'), revokeObjectURL: vi.fn() })
})

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

function renderPanel(value: TournamentResponse, canExportPlayers: boolean, onUpdated = vi.fn()) {
  render(
    <MemoryRouter>
      <OrganizerPanel tournament={value} onUpdated={onUpdated} canHardDelete={canExportPlayers} canExportPlayers={canExportPlayers} />
    </MemoryRouter>,
  )
  return onUpdated
}

describe('player export', () => {
  it('is offered only to an admin, in any status', () => {
    renderPanel(tournament('FINISHED'), true)
    expect(screen.getByRole('button', { name: 'Descargar Excel de jugadores' })).toBeInTheDocument()
  })

  it('is not offered to an organizer', () => {
    renderPanel(tournament('ACTIVE'), false)
    expect(screen.queryByRole('button', { name: 'Descargar Excel de jugadores' })).not.toBeInTheDocument()
  })

  it('downloads the file with the bearer token and the name sent by the server', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    renderPanel(tournament('IN_PROGRESS'), true)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Descargar Excel de jugadores' }))

    await waitFor(() => expect(click).toHaveBeenCalled())
    const call = vi.mocked(globalThis.fetch).mock.calls.find(([input]) => String(input).endsWith('/tournaments/1/players/export'))
    expect(new Headers(call?.[1]?.headers).get('Authorization')).toBe('Bearer jwt')
    const anchor = click.mock.contexts[0] as HTMLAnchorElement
    expect(anchor.download).toBe('jugadores-techcup-2026-2.xlsx')
    expect(URL.createObjectURL).toHaveBeenCalled()
  })

  it('reads plain and RFC 5987 file names from Content-Disposition', () => {
    expect(filenameFromContentDisposition('attachment; filename="jugadores-a.xlsx"')).toBe('jugadores-a.xlsx')
    expect(filenameFromContentDisposition("attachment; filename*=UTF-8''jugadores-%C3%B1.xlsx")).toBe('jugadores-ñ.xlsx')
    expect(filenameFromContentDisposition('attachment; filename=plain.xlsx')).toBe('plain.xlsx')
    expect(filenameFromContentDisposition(null)).toBeNull()
    expect(filenameFromContentDisposition('attachment')).toBeNull()
  })
})

describe('venue photo', () => {
  it('replaces the photo of a venue in an active tournament', async () => {
    const onUpdated = renderPanel(tournament('ACTIVE'), false)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Cambiar foto' }))
    const dialog = screen.getByRole('dialog')
    const file = new File([new Uint8Array([137, 80, 78, 71])], 'cancha.png', { type: 'image/png' })
    await user.upload(within(dialog).getByLabelText('Elegir archivo'), file)
    await user.click(within(dialog).getByRole('button', { name: 'Guardar foto' }))

    await waitFor(() =>
      expect(
        vi
          .mocked(globalThis.fetch)
          .mock.calls.some(([input, init]) => String(input).endsWith('/tournaments/1/venues/3/image') && init?.method === 'PUT'),
      ).toBe(true),
    )
    await waitFor(() => expect(onUpdated).toHaveBeenCalled())
    const updater = onUpdated.mock.calls[0]?.[0] as (previous: TournamentResponse) => TournamentResponse
    expect(updater(tournament('ACTIVE')).venues[0]?.imageFileId).toBe('venue-new')
  })

  it('offers no photo change while the tournament is suspended or finished', () => {
    renderPanel(tournament('SUSPENDED'), false)
    expect(screen.queryByRole('button', { name: 'Cambiar foto' })).not.toBeInTheDocument()
  })
})
