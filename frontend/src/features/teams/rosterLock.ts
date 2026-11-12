import type { TeamResponse, TournamentStatus } from '@/types/api'
import { MAX_TEAM_MEMBERS, MIN_TEAM_MEMBERS } from './teamSize'

/**
 * A team registered (APPROVED) in a tournament that has started (IN_PROGRESS, or SUSPENDED while in
 * progress) is `locked`: its roster is frozen until the tournament finishes, so it neither accepts
 * join requests nor sends invitations, and nobody leaves or is removed. Approved in a tournament that
 * has not started yet it is not locked: it may still add and remove players as long as it stays
 * eligible (the backend answers 409 with the reason otherwise). The backend enforces both; these
 * helpers only keep the UI from offering actions it would refuse.
 */
export const ROSTER_FROZEN_NOTICE = 'Su equipo está inscrito en un torneo en curso; no puede sumar jugadores hasta que termine.'
export const TEAM_NOT_RECRUITING_HINT = 'Este equipo está inscrito en un torneo en curso y no recibe nuevos jugadores.'

/** Shown next to a disabled "Salir del equipo": the roster is frozen for members leaving too. */
export const LEAVE_FROZEN_HINT =
  'El equipo está jugando un torneo que ya inició: no puede salir de él hasta que el torneo finalice.'

/** Info banner on "Mi equipo" while the team is locked. */
export const TEAM_LOCKED_NOTICE =
  'El equipo está jugando un torneo que ya inició. No es posible modificar el nombre ni los colores, ni sumar o retirar integrantes hasta que el torneo finalice.'

/** True once the tournament has started: IN_PROGRESS, or SUSPENDED while it was in progress. */
export function tournamentHasStarted(status: TournamentStatus, suspendedFromStatus: TournamentStatus | null): boolean {
  return status === 'IN_PROGRESS' || (status === 'SUSPENDED' && suspendedFromStatus === 'IN_PROGRESS')
}

/** What an APPROVED registration means for the roster, shown to the captain on the tournament page. */
export function approvedRosterNote(status: TournamentStatus, suspendedFromStatus: TournamentStatus | null): string {
  if (status === 'FINISHED') return 'Su equipo participó en el torneo.'
  if (tournamentHasStarted(status, suspendedFromStatus)) {
    return 'Su equipo participa en el torneo. Como el torneo ya inició, la plantilla queda bloqueada hasta que finalice.'
  }
  return (
    'Su equipo participa en el torneo. Hasta que el torneo inicie puede sumar o retirar integrantes, siempre que el ' +
    `equipo siga cumpliendo los requisitos (entre ${MIN_TEAM_MEMBERS} y ${MAX_TEAM_MEMBERS} integrantes y más de la ` +
    'mitad de los programas organizadores). Cuando inicie, la plantilla queda bloqueada.'
  )
}

/** Confirmation asked before a member leaves the team. */
export function leaveTeamQuestion(teamName: string): string {
  return `¿Seguro que quiere salir de «${teamName}»? Tendrá que volver a pedir unirse o recibir una invitación para regresar.`
}

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
