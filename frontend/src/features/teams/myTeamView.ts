import type { TeamResponse, UserResponse } from '@/types/api'

/**
 * Mirrors the backend rule (`TeamService.create`): only a user who literally holds PLAYER may found a
 * team, because the founder becomes its first member and captain. ADMIN does not imply it here, and a
 * user who already belongs to an active team cannot create another one.
 */
export function canCreateTeam(user: Pick<UserResponse, 'roles' | 'teamId'> | null | undefined): boolean {
  return !!user && user.roles.includes('PLAYER') && (user.teamId ?? null) === null
}

/**
 * What "Mi equipo" shows:
 * - `not-player`: no team and no PLAYER role (e.g. an ADMIN): teams cannot be created from this account;
 * - `needs-profile`: no team and no sport profile yet (a profile is required to create or join a team);
 * - `create`: no team, profile ready → the create-team form (the creator becomes CAPTAIN);
 * - `member`: belongs to a team captained by someone else → read-only summary;
 * - `captain`: captains the team → management view.
 */
export type MyTeamView = 'not-player' | 'needs-profile' | 'create' | 'member' | 'captain'

export interface MyTeamViewInput {
  /** The viewer's active team (`GET /teams/mine`), or null. */
  team: TeamResponse | null
  userId: number | null
  hasProfile: boolean
  /** Whether the viewer literally holds PLAYER; defaults to true. */
  isPlayer?: boolean
}

export function selectMyTeamView({ team, userId, hasProfile, isPlayer = true }: MyTeamViewInput): MyTeamView {
  if (team) return userId !== null && team.captain.id === userId ? 'captain' : 'member'
  if (!isPlayer) return 'not-player'
  return hasProfile ? 'create' : 'needs-profile'
}
