import type { TeamResponse } from '@/types/api'

/**
 * What "Mi equipo" shows:
 * - `needs-profile`: no team and no sport profile yet (a profile is required to create or join a team);
 * - `create`: no team, profile ready → the create-team form (the creator becomes CAPTAIN);
 * - `member`: belongs to a team captained by someone else → read-only summary;
 * - `captain`: captains the team → management view.
 */
export type MyTeamView = 'needs-profile' | 'create' | 'member' | 'captain'

export interface MyTeamViewInput {
  /** The viewer's active team (`GET /teams/mine`), or null. */
  team: TeamResponse | null
  userId: number | null
  hasProfile: boolean
}

export function selectMyTeamView({ team, userId, hasProfile }: MyTeamViewInput): MyTeamView {
  if (team) return userId !== null && team.captain.id === userId ? 'captain' : 'member'
  return hasProfile ? 'create' : 'needs-profile'
}
