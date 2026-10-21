/**
 * Roster bounds, mirrored from the backend team rules (`TeamEligibility.MIN_MEMBERS` / `MAX_MEMBERS`).
 * The minimum is needed to register in a tournament; the maximum caps new members (join requests and
 * invitations). This is the only place the frontend states them.
 */
export const MIN_TEAM_MEMBERS = 8
export const MAX_TEAM_MEMBERS = 13

/** Eligibility checklist line. */
export const TEAM_SIZE_RULE = `Entre ${MIN_TEAM_MEMBERS} y ${MAX_TEAM_MEMBERS} integrantes.`

/** "9 / 13 integrantes" (or "9 / 13" when `withNoun` is false). */
export function rosterCount(memberCount: number, withNoun = true): string {
  return withNoun ? `${memberCount} / ${MAX_TEAM_MEMBERS} integrantes` : `${memberCount} / ${MAX_TEAM_MEMBERS}`
}
