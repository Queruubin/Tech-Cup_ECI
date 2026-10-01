import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { JoinRequestResponse, TeamResponse, UserResponse } from '@/types/api'
import { BasicInfoForm, RELATION_LOCKED_HINT } from './components/BasicInfoForm'
import { InvitePlayerModal } from './components/InvitePlayerModal'
import {
  canInviteWithTeam,
  countPendingInvitations,
  inviteButtonState,
  pendingInvitedPlayerIds,
  splitInvitations,
} from './invitations'

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

function invitation(id: number, playerId: number, status: JoinRequestResponse['status'], createdAt: string): JoinRequestResponse {
  return {
    id,
    teamId: team.id,
    teamName: team.name,
    playerId,
    playerName: `Jugador ${playerId}`,
    position: 'DEFENDER',
    jerseyNumber: playerId,
    status,
    direction: 'INVITATION',
    message: null,
    createdAt,
  }
}

describe('invitation button visibility', () => {
  it('lets only the captain of an ACTIVE team invite', () => {
    expect(canInviteWithTeam(team, 1)).toBe(true)
    expect(canInviteWithTeam(team, 2)).toBe(false)
    expect(canInviteWithTeam({ ...team, status: 'INACTIVE' }, 1)).toBe(false)
    // A team playing a tournament has a frozen roster: no "Invitar" at all.
    expect(canInviteWithTeam({ ...team, locked: true }, 1)).toBe(false)
    expect(inviteButtonState({ userId: 10, teamId: null }, canInviteWithTeam({ ...team, locked: true }, 1), new Set())).toBe('hidden')
    // Organizers (no team) and anonymous viewers never invite.
    expect(canInviteWithTeam(null, 1)).toBe(false)
    expect(canInviteWithTeam(team, null)).toBe(false)
  })

  it('hides the button, offers it, or marks the player as already invited', () => {
    const invited = pendingInvitedPlayerIds([
      invitation(1, 10, 'PENDING', '2026-09-01T10:00:00Z'),
      invitation(2, 11, 'REJECTED', '2026-09-01T11:00:00Z'),
      invitation(3, 12, 'CANCELLED', '2026-09-01T12:00:00Z'),
    ])
    expect([...invited]).toEqual([10])

    expect(inviteButtonState({ userId: 10, teamId: null }, false, invited)).toBe('hidden')
    expect(inviteButtonState({ userId: 10, teamId: null }, true, invited)).toBe('invited')
    expect(inviteButtonState({ userId: 11, teamId: null }, true, invited)).toBe('invite')
    expect(inviteButtonState({ userId: 13, teamId: 9 }, true, invited)).toBe('hidden')
    expect(pendingInvitedPlayerIds(null).size).toBe(0)
  })
})

describe('received invitations', () => {
  it('splits pending invitations from history, newest first', () => {
    const list = [
      invitation(1, 5, 'REJECTED', '2026-09-01T10:00:00Z'),
      invitation(2, 5, 'PENDING', '2026-09-02T10:00:00Z'),
      invitation(3, 5, 'PENDING', '2026-09-03T10:00:00Z'),
      invitation(4, 5, 'CANCELLED', '2026-09-04T10:00:00Z'),
    ]
    const { pending, history } = splitInvitations(list)
    expect(pending.map((item) => item.id)).toEqual([3, 2])
    expect(history.map((item) => item.id)).toEqual([4, 1])
    expect(countPendingInvitations(list)).toBe(2)
    expect(countPendingInvitations(null)).toBe(0)
  })
})

describe('InvitePlayerModal', () => {
  const player = { userId: 10, fullName: 'Luis Gómez', position: 'FORWARD' as const, jerseyNumber: 9, photoFileId: null }

  it('sends the trimmed message, or null when it is blank', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const { unmount } = render(
      <InvitePlayerModal player={player} teamName="Leones FC" loading={false} error={null} fieldErrors={{}} onSubmit={onSubmit} onClose={() => undefined} />,
    )
    expect(screen.getByText('Invitará a Luis Gómez a unirse a Leones FC.')).toBeInTheDocument()
    expect(screen.getByLabelText(/Mensaje/)).toHaveAttribute('maxLength', '500')
    await user.click(screen.getByRole('button', { name: 'Enviar invitación' }))
    expect(onSubmit).toHaveBeenLastCalledWith(null)

    await user.type(screen.getByLabelText(/Mensaje/), '  ¡Te esperamos!  ')
    await user.click(screen.getByRole('button', { name: 'Enviar invitación' }))
    expect(onSubmit).toHaveBeenLastCalledWith('¡Te esperamos!')
    unmount()
  })
})

describe('BasicInfoForm relation lock', () => {
  const me: UserResponse = {
    id: 3,
    fullName: 'Ana Pérez',
    email: 'ana@gmail.com',
    schoolRelation: 'STUDENT',
    academicProgram: 'SYSTEMS_ENGINEERING',
    semester: 5,
    status: 'ACTIVE',
    birthDate: null,
    documentType: null,
    documentNumber: null,
    roles: ['PLAYER'],
    hasProfile: true,
    teamId: null,
  }

  it('disables the relation for non-admins, keeps the semester editable and sends the current relation', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(<BasicInfoForm user={me} loading={false} error={null} fieldErrors={{}} canEditRelation={false} onSubmit={onSubmit} />)

    expect(screen.getByLabelText(/Relación con la Escuela/)).toBeDisabled()
    expect(screen.getByText(RELATION_LOCKED_HINT)).toBeInTheDocument()
    const semester = screen.getByLabelText(/Semestre/)
    expect(semester).toBeEnabled()

    await user.clear(semester)
    await user.type(semester, '6')
    await user.click(screen.getByRole('button', { name: 'Actualizar información' }))
    expect(onSubmit).toHaveBeenCalledWith({
      fullName: 'Ana Pérez',
      schoolRelation: 'STUDENT',
      academicProgram: 'SYSTEMS_ENGINEERING',
      semester: 6,
    })
  })

  it('lets an admin change the relation', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(
      <BasicInfoForm user={me} loading={false} error={null} fieldErrors={{}} canEditRelation submitLabel="Guardar cambios" onSubmit={onSubmit} />,
    )

    const relation = screen.getByLabelText(/Relación con la Escuela/)
    expect(relation).toBeEnabled()
    expect(screen.queryByText(RELATION_LOCKED_HINT)).not.toBeInTheDocument()
    await user.selectOptions(relation, 'GRADUATE')
    expect(screen.queryByLabelText(/Semestre/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ schoolRelation: 'GRADUATE', semester: null }))
  })
})
