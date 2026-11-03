import type { Role, TeamResponse } from '@/types/api'

export interface TeamPhotoViewer {
  id: number
  roles: Role[]
}

/**
 * Who may change or remove a team photo: its captain (who holds CAPTAIN), an ORGANIZER or an
 * ADMIN. Mirrors `TeamService.replacePhoto`; the photo is never frozen by a tournament, so the
 * team's `locked` flag is deliberately ignored.
 */
export function canManageTeamPhoto(viewer: TeamPhotoViewer | null, team: Pick<TeamResponse, 'captain'> | null): boolean {
  if (!viewer || !team) return false
  if (viewer.roles.includes('ADMIN') || viewer.roles.includes('ORGANIZER')) return true
  return team.captain.id === viewer.id && viewer.roles.includes('CAPTAIN')
}

export const REMOVE_TEAM_PHOTO_QUESTION = 'La foto dejará de verse en las tarjetas y en la página del equipo.'
