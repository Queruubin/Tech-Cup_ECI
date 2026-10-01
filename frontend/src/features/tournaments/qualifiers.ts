/**
 * Number of teams that go from the group table into the knockout, mirroring the backend rule
 * (`MatchService.qualifierCount`): 8 or more teams play quarter-finals, 4 to 7 play semi-finals,
 * 2 or 3 go straight to the final. Fewer than 2 teams cannot play a knockout (0 = no cut line).
 */
export function qualifierCount(teams: number): number {
  if (teams >= 8) return 8
  if (teams >= 4) return 4
  if (teams >= 2) return 2
  return 0
}
