import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { RegistrationResponse, TeamResponse } from '@/types/api'
import { MyTeamCard, canRegisterAgain, registrationHref } from './components/MyTeamCard'

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
