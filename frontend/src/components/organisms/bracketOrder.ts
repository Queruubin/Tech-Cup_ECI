import type { MatchPhase, MatchResponse } from '@/types/api'

/** Pure bracket helpers behind `BracketView` (kept out of the component for fast refresh and tests). */

export const KNOCKOUT_PHASES: MatchPhase[] = ['QUARTERFINAL', 'SEMIFINAL', 'FINAL']

/** Returns the winning team id of a knockout match (walkover, score, then penalties), or null. */
export function matchWinnerId(match: MatchResponse): number | null {
  if (match.status === 'CANCELLED') return match.walkoverWinnerTeamId
  if (match.status !== 'PLAYED' || match.homeScore === null || match.awayScore === null) return null
  if (match.homeScore !== match.awayScore) return match.homeScore > match.awayScore ? match.homeTeam.id : match.awayTeam.id
  if (match.homePenalties !== null && match.awayPenalties !== null && match.homePenalties !== match.awayPenalties) {
    return match.homePenalties > match.awayPenalties ? match.homeTeam.id : match.awayTeam.id
  }
  return null
}

/**
 * Orders every knockout phase so that the two matches feeding the same next-round match are
 * neighbours (drawn one above the other, next to the match they feed): the latest phase keeps its
 * bracket order (match id); each earlier phase follows the order of the next one, HOME feeder
 * first. The feeder comes from the stored link (`nextMatchId` / `nextSlot`); knockout rounds drawn
 * before the links existed (folded: W1vW4, W2vW3) are matched by the team that went through.
 * Matches that feed nothing yet keep their id order at the end.
 */
export function orderKnockoutPhases(
  phases: readonly { phase: MatchPhase; matches: MatchResponse[] }[],
): Map<MatchPhase, MatchResponse[]> {
  const byId = (a: MatchResponse, b: MatchResponse) => a.id - b.id
  const present = KNOCKOUT_PHASES.filter((phase) => phases.some((item) => item.phase === phase && item.matches.length > 0))
  const ordered = new Map<MatchPhase, MatchResponse[]>()
  let later: MatchResponse[] | null = null
  for (const phase of [...present].reverse()) {
    const matches = [...(phases.find((item) => item.phase === phase)?.matches ?? [])].sort(byId)
    if (later === null) {
      ordered.set(phase, matches)
      later = matches
      continue
    }
    const remaining = new Set(matches.map((match) => match.id))
    const result: MatchResponse[] = []
    const take = (match: MatchResponse | undefined) => {
      if (match && remaining.has(match.id)) {
        remaining.delete(match.id)
        result.push(match)
      }
    }
    for (const next of later) {
      for (const slot of ['HOME', 'AWAY'] as const) {
        const linked = matches.find((match) => match.nextMatchId === next.id && match.nextSlot === slot)
        const teamId = slot === 'HOME' ? next.homeTeam.id : next.awayTeam.id
        const unlinked = matches.filter((match) => remaining.has(match.id) && match.nextMatchId === null)
        // Legacy rows (no link): the match whose winner holds the slot, else the one holding that team.
        const inferred =
          unlinked.find((match) => matchWinnerId(match) === teamId) ??
          unlinked.find((match) => match.homeTeam.id === teamId || match.awayTeam.id === teamId)
        take(linked ?? inferred)
      }
    }
    for (const match of matches) take(match)
    ordered.set(phase, result)
    later = result
  }
  return ordered
}
