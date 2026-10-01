import type { JoinRequestResponse, PlayerProfileResponse, TeamResponse } from '@/types/api'

/**
 * True when `userId` captains `team`, the team is ACTIVE and its roster is not frozen by a
 * tournament (`locked`), i.e. may send invitations.
 */
export function canInviteWithTeam(team: TeamResponse | null | undefined, userId: number | null | undefined): boolean {
  return !!team && userId != null && team.status === 'ACTIVE' && !team.locked && team.captain.id === userId
}

export type InviteButtonState = 'hidden' | 'invite' | 'invited'

/**
 * State of the "Invitar" button on a free-agent card: hidden unless the viewer captains an active team
 * (and the player is not already in a team); "invited" when this team already has a pending invitation.
 */
export function inviteButtonState(
  player: Pick<PlayerProfileResponse, 'userId' | 'teamId'>,
  canInvite: boolean,
  pendingInvitedPlayerIds: ReadonlySet<number>,
): InviteButtonState {
  if (!canInvite || player.teamId) return 'hidden'
  return pendingInvitedPlayerIds.has(player.userId) ? 'invited' : 'invite'
}

/** Player ids with a PENDING invitation in the given list. */
export function pendingInvitedPlayerIds(invitations: readonly JoinRequestResponse[] | null | undefined): Set<number> {
  return new Set((invitations ?? []).filter((item) => item.status === 'PENDING').map((item) => item.playerId))
}

function newestFirst(a: JoinRequestResponse, b: JoinRequestResponse): number {
  return b.createdAt.localeCompare(a.createdAt)
}

/** Splits received invitations into pending ones and history, both newest first. */
export function splitInvitations(invitations: readonly JoinRequestResponse[] | null | undefined): {
  pending: JoinRequestResponse[]
  history: JoinRequestResponse[]
} {
  const sorted = [...(invitations ?? [])].sort(newestFirst)
  return {
    pending: sorted.filter((item) => item.status === 'PENDING'),
    history: sorted.filter((item) => item.status !== 'PENDING'),
  }
}

export function countPendingInvitations(invitations: readonly JoinRequestResponse[] | null | undefined): number {
  return (invitations ?? []).filter((item) => item.status === 'PENDING').length
}
