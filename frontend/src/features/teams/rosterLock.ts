import type { TeamResponse } from '@/types/api'

/**
 * A team registered (APPROVED) in an ACTIVE or IN_PROGRESS tournament is `locked`: its roster is
 * frozen until the tournament finishes, so it neither accepts join requests nor sends invitations.
 * The backend enforces it; these helpers only keep the UI from offering actions it would refuse.
 */
export const ROSTER_FROZEN_NOTICE = 'Su equipo está inscrito en un torneo en curso; no puede sumar jugadores hasta que termine.'
export const TEAM_NOT_RECRUITING_HINT = 'Este equipo está inscrito en un torneo en curso y no recibe nuevos jugadores.'

/** Maximum roster size, mirrored from the backend team rules. */
export const MAX_TEAM_MEMBERS = 12

/** True when the team is ACTIVE but locked by a tournament, i.e. cannot take new players. */
export function isRosterFrozen(team: TeamResponse | null | undefined): boolean {
  return !!team && team.status === 'ACTIVE' && team.locked
}

/**
 * State of "Solicitar unirme" on a team page:
 * - `hidden`: the viewer cannot request (not a player, already in a team, member of this team, or
 *   the team is inactive or full);
 * - `frozen`: the team would qualify but its roster is frozen by a tournament;
 * - `open`: the viewer may send a request.
 */
export type JoinRequestAvailability = 'hidden' | 'frozen' | 'open'

export interface JoinRequestViewer {
  userId: number | null
  isPlayer: boolean
  /** The viewer's active team, if any. */
  teamId: number | null
}

export function joinRequestAvailability(
  team: TeamResponse | null | undefined,
  viewer: JoinRequestViewer,
): JoinRequestAvailability {
  if (!team || !viewer.isPlayer || viewer.teamId) return 'hidden'
  if (team.status !== 'ACTIVE' || team.memberCount >= MAX_TEAM_MEMBERS) return 'hidden'
  if (team.members.some((member) => member.userId === viewer.userId)) return 'hidden'
  return team.locked ? 'frozen' : 'open'
}
