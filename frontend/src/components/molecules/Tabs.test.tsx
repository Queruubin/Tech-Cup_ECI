import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TournamentDetailPage } from '@/features/tournaments/pages/TournamentDetailPage'
import { useAuthStore } from '@/store/auth.store'
import type { TournamentResponse, UserResponse } from '@/types/api'
import { Tabs } from './Tabs'

const TABS = [
  { id: 'info', label: 'Información' },
  { id: 'manage', label: 'Gestión', highlight: true },
]

describe('Tabs highlight', () => {
  it('tints a highlighted tab whether idle or active and keeps the active indicator', () => {
    const { rerender } = render(<Tabs tabs={TABS} active="info" onChange={() => undefined} />)
    const manage = screen.getByRole('tab', { name: 'Gestión' })
    const info = screen.getByRole('tab', { name: 'Información' })
    expect(manage.className).toContain('bg-brand-50')
    expect(manage.className).toContain('text-stone-600')
    expect(manage.className).not.toContain('text-stone-500')
    expect(info.className).not.toContain('bg-brand-50')

    rerender(<Tabs tabs={TABS} active="manage" onChange={() => undefined} />)
    expect(screen.getByRole('tab', { name: 'Gestión' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Gestión' }).className).toContain('bg-brand-50')
    expect(screen.getByRole('tab', { name: 'Gestión' }).className).toContain('border-brand-600')
  })
})

// Pages load in several requests and these tests click through dialogs: allow for a busy runner.
vi.setConfig({ testTimeout: 15_000 })

describe('TournamentDetailPage "Gestión" tab', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    useAuthStore.setState({ token: null, expiresAt: null, user: null })
  })

  it('is highlighted for organizers', async () => {
    const tournament: TournamentResponse = {
      id: 1,
      name: 'TechCup 2026-2',
      startDate: '2026-09-01',
      endDate: '2026-11-30',
      registrationDeadline: '2026-08-25',
      maxTeams: 8,
      fee: 50000,
      status: 'ACTIVE',
      rulebookFileId: null,
      venues: [],
      approvedTeams: 0,
      suspendedFromStatus: null,
    }
    const organizer: UserResponse = {
      id: 3,
      fullName: 'Olga Ruiz',
      email: 'olga@escuelaing.edu.co',
      schoolRelation: null,
      academicProgram: null,
      semester: null,
      status: 'ACTIVE',
      birthDate: null,
      documentType: null,
      documentNumber: null,
      roles: ['ORGANIZER'],
      hasProfile: false,
      teamId: null,
    }
    useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user: organizer })
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const body = String(input).endsWith('/tournaments/1') ? tournament : []
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    })

    render(
      <MemoryRouter initialEntries={['/tournaments/1']}>
        <Routes>
          <Route path="/tournaments/:id" element={<TournamentDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )
    const manage = await screen.findByRole('tab', { name: 'Gestión' }, { timeout: 3000 })
    expect(manage.className).toContain('bg-brand-50')
    expect(screen.getByRole('tab', { name: 'Información' }).className).not.toContain('bg-brand-50')
  })
})
