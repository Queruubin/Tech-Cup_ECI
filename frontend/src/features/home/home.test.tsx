import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { RegistrationResponse, TeamResponse } from '@/types/api'
import { MyTeamCard, canRegisterAgain, registrationHref } from './components/MyTeamCard'
import { PendingInvitationsAlert } from './components/PendingInvitationsAlert'

const team: TeamResponse = {
  id: 4,
  name: 'Leones FC',
  colors: 'Rojo',
  status: 'ACTIVE',
  captain: { id: 1, fullName: 'Ana Pérez' },
  members: [],
  memberCount: 7,
  locked: false,
}

function registration(status: RegistrationResponse['status']): RegistrationResponse {
  return {
    id: 9,
    tournamentId: 2,
    teamId: team.id,
    teamName: team.name,
    receiptFileId: null,
    status,
    reviewNote: null,
    createdAt: '2026-01-10T12:00:00Z',
    reviewedAt: null,
  }
}

describe('MyTeamCard registration CTA', () => {
  it('allows a new registration when there is none or the previous one was rejected/cancelled', () => {
    expect(canRegisterAgain(null)).toBe(true)
    expect(canRegisterAgain(registration('REJECTED'))).toBe(true)
    expect(canRegisterAgain(registration('CANCELLED'))).toBe(true)
    expect(canRegisterAgain(registration('UNDER_REVIEW'))).toBe(false)
    expect(canRegisterAgain(registration('APPROVED'))).toBe(false)
  })

  it('deep-links to the registration tab of the current tournament', () => {
    expect(registrationHref(2)).toBe('/tournaments/2?tab=register')
    expect(registrationHref(null)).toBe('/tournaments')
  })

  it('shows the CTA for a rejected registration and hides it for one under review', () => {
    const { rerender } = render(
      <MemoryRouter>
        <MyTeamCard team={team} registration={registration('REJECTED')} isCaptain isPlayer tournamentOpen tournamentId={2} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: /Inscribir equipo/ })).toHaveAttribute('href', '/tournaments/2?tab=register')

    rerender(
      <MemoryRouter>
        <MyTeamCard team={team} registration={registration('UNDER_REVIEW')} isCaptain isPlayer tournamentOpen tournamentId={2} />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('link', { name: /Inscribir equipo/ })).not.toBeInTheDocument()
  })
})

describe('MyTeamCard without a team', () => {
  it('lets any player create a team or browse existing ones', () => {
    render(
      <MemoryRouter>
        <MyTeamCard team={null} registration={null} isCaptain={false} isPlayer tournamentOpen={false} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: 'Crear equipo' })).toHaveAttribute('href', '/my-team')
    expect(screen.getByRole('link', { name: 'Ver equipos' })).toHaveAttribute('href', '/teams')
  })

  it('offers nothing to users who are not players', () => {
    render(
      <MemoryRouter>
        <MyTeamCard team={null} registration={null} isCaptain={false} isPlayer={false} tournamentOpen={false} />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('link', { name: 'Crear equipo' })).not.toBeInTheDocument()
  })
})

describe('PendingInvitationsAlert', () => {
  it('announces pending invitations and links to the requests page', () => {
    const { rerender } = render(
      <MemoryRouter>
        <PendingInvitationsAlert count={3} />
      </MemoryRouter>,
    )
    expect(screen.getByText('Tiene 3 invitaciones pendientes')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver invitaciones' })).toHaveAttribute('href', '/my-requests')

    rerender(
      <MemoryRouter>
        <PendingInvitationsAlert count={1} />
      </MemoryRouter>,
    )
    expect(screen.getByText('Tiene 1 invitación pendiente')).toBeInTheDocument()
  })

  it('renders nothing without pending invitations', () => {
    const { container } = render(
      <MemoryRouter>
        <PendingInvitationsAlert count={0} />
      </MemoryRouter>,
    )
    expect(container).toBeEmptyDOMElement()
  })
})
