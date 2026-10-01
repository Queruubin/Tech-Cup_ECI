import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { JoinRequestResponse, TeamMember, TeamResponse } from '@/types/api'
import { JoinRequestsPanel } from './components/JoinRequestsPanel'
import { SentInvitationsPanel } from './components/SentInvitationsPanel'
import { selectMyTeamView } from './myTeamView'
import {
  isRosterFrozen,
  joinRequestAvailability,
  ROSTER_FROZEN_NOTICE,
  TEAM_NOT_RECRUITING_HINT,
} from './rosterLock'

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

describe('selectMyTeamView', () => {
  it('asks for a sport profile first when the player has no team and no profile', () => {
    expect(selectMyTeamView({ team: null, userId: 2, hasProfile: false })).toBe('needs-profile')
  })

  it('offers the create-team form to a player with a profile and no team', () => {
    expect(selectMyTeamView({ team: null, userId: 2, hasProfile: true })).toBe('create')
  })

  it('shows a read-only summary to a member who is not the captain', () => {
    expect(selectMyTeamView({ team, userId: 2, hasProfile: true })).toBe('member')
  })

  it('shows the management view to the captain', () => {
    expect(selectMyTeamView({ team, userId: 1, hasProfile: true })).toBe('captain')
  })

  it('never treats an unknown viewer as captain', () => {
    expect(selectMyTeamView({ team, userId: null, hasProfile: false })).toBe('member')
  })
})

describe('roster freeze of a team playing a tournament', () => {
  const locked: TeamResponse = { ...team, locked: true }
  const freePlayer = { userId: 9, isPlayer: true, teamId: null }

  it('treats only an ACTIVE locked team as frozen', () => {
    expect(isRosterFrozen(locked)).toBe(true)
    expect(isRosterFrozen(team)).toBe(false)
    expect(isRosterFrozen({ ...locked, status: 'INACTIVE' })).toBe(false)
    expect(isRosterFrozen(null)).toBe(false)
  })

  it('offers "Solicitar unirme" only to free players of an open team and explains a frozen one', () => {
    expect(joinRequestAvailability(team, freePlayer)).toBe('open')
    expect(joinRequestAvailability(locked, freePlayer)).toBe('frozen')
    expect(TEAM_NOT_RECRUITING_HINT).toBe('Este equipo está inscrito en un torneo en curso y no recibe nuevos jugadores.')
  })

  it('hides "Solicitar unirme" when the viewer could not request anyway', () => {
    expect(joinRequestAvailability(locked, { ...freePlayer, isPlayer: false })).toBe('hidden')
    expect(joinRequestAvailability(locked, { ...freePlayer, teamId: 8 })).toBe('hidden')
    expect(joinRequestAvailability({ ...locked, status: 'INACTIVE' }, freePlayer)).toBe('hidden')
    expect(joinRequestAvailability({ ...locked, memberCount: 12 }, freePlayer)).toBe('hidden')
    const member: TeamMember = { userId: 9, fullName: 'Pedro', position: 'DEFENDER', jerseyNumber: 4, academicProgram: 'OTHER', photoFileId: null }
    expect(joinRequestAvailability({ ...team, members: [member] }, freePlayer)).toBe('hidden')
    expect(joinRequestAvailability(null, freePlayer)).toBe('hidden')
  })
})

describe('captain panels of a frozen roster', () => {
  const pending = (id: number, direction: JoinRequestResponse['direction']): JoinRequestResponse => ({
    id,
    teamId: team.id,
    teamName: team.name,
    playerId: 20 + id,
    playerName: `Jugador ${id}`,
    position: 'DEFENDER',
    jerseyNumber: id,
    status: 'PENDING',
    direction,
    message: null,
    createdAt: '2026-09-30T12:00:00Z',
  })

  it('explains the freeze and keeps only "Rechazar" on join requests', () => {
    const { rerender } = render(
      <JoinRequestsPanel requests={[pending(1, 'REQUEST')]} loading={false} error={null} busyId={null} onAccept={vi.fn()} onReject={vi.fn()} rosterFrozen />,
    )
    expect(screen.getByText(ROSTER_FROZEN_NOTICE)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Aceptar' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Rechazar' })).toBeEnabled()

    rerender(
      <JoinRequestsPanel requests={[pending(1, 'REQUEST')]} loading={false} error={null} busyId={null} onAccept={vi.fn()} onReject={vi.fn()} />,
    )
    expect(screen.queryByText(ROSTER_FROZEN_NOTICE)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Aceptar' })).toBeInTheDocument()
  })

  it('explains the freeze, keeps "Cancelar invitación" and stops suggesting new invitations', () => {
    const { rerender } = render(
      <MemoryRouter>
        <SentInvitationsPanel invitations={[pending(2, 'INVITATION')]} loading={false} error={null} busyId={null} onCancel={vi.fn()} rosterFrozen />
      </MemoryRouter>,
    )
    expect(screen.getByText(ROSTER_FROZEN_NOTICE)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancelar invitación' })).toBeEnabled()

    rerender(
      <MemoryRouter>
        <SentInvitationsPanel invitations={[]} loading={false} error={null} busyId={null} onCancel={vi.fn()} rosterFrozen />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: 'Ver jugadores disponibles' })).not.toBeInTheDocument()

    rerender(
      <MemoryRouter>
        <SentInvitationsPanel invitations={[]} loading={false} error={null} busyId={null} onCancel={vi.fn()} />
      </MemoryRouter>,
    )
    expect(screen.queryByText(ROSTER_FROZEN_NOTICE)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ver jugadores disponibles' })).toBeInTheDocument()
  })
})
