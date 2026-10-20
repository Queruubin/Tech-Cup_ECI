import type { SelectOption } from '@/components/atoms/Select'
import {
  MATCH_PHASES,
  type CreateKnockoutRequest,
  type GroupResponse,
  type KnockoutSize,
  type KnockoutSuggestion,
  type MatchPhase,
  type MatchResponse,
  type RegistrationResponse,
} from '@/types/api'

/** Pure helpers behind the organizer's group stage and knockout builder (kept out of the components for testing). */

export const NO_GROUPS_HINT = 'Cree grupos y asigne equipos para programar partidos.'

export const KNOCKOUT_SIZE_LABELS: Record<KnockoutSize, string> = {
  2: 'Final (2 equipos)',
  4: 'Semifinales (4 equipos)',
  8: 'Cuartos de final (8 equipos)',
}

export interface TeamOption {
  id: number
  name: string
}

/** APPROVED teams of the tournament, by name. */
export function approvedTeams(registrations: readonly RegistrationResponse[]): TeamOption[] {
  return registrations
    .filter((registration) => registration.status === 'APPROVED')
    .map((registration) => ({ id: registration.teamId, name: registration.teamName }))
    .sort((a, b) => a.name.localeCompare(b.name, 'es'))
}

/** APPROVED teams that are in no group yet, by name. */
export function unassignedTeams(
  registrations: readonly RegistrationResponse[],
  groups: readonly GroupResponse[],
): TeamOption[] {
  const assigned = new Set(groups.flatMap((group) => group.teams.map((team) => team.id)))
  return approvedTeams(registrations).filter((team) => !assigned.has(team.id))
}

/** True once the tournament has any knockout match: the group stage is then frozen. */
export function knockoutStarted(matches: readonly Pick<MatchResponse, 'phase'>[] | null | undefined): boolean {
  return !!matches?.some((match) => match.phase !== 'GROUP')
}

/** Latest phase present among the matches; `null` without matches. */
export function latestPhase(matches: readonly Pick<MatchResponse, 'phase'>[] | null | undefined): MatchPhase | null {
  let latest = -1
  for (const match of matches ?? []) latest = Math.max(latest, MATCH_PHASES.indexOf(match.phase))
  return latest < 0 ? null : (MATCH_PHASES[latest] as MatchPhase)
}

/** Options for the home/away selects of a group match: only the teams of that group. */
export function groupTeamOptions(group: GroupResponse | undefined): SelectOption[] {
  return (group?.teams ?? []).map((team) => ({ value: String(team.id), label: team.name }))
}

/** One editable pairing of the knockout builder (select values, `''` while empty). */
export interface PairingDraft {
  homeTeamId: string
  awayTeamId: string
}

export function emptyPairings(size: KnockoutSize): PairingDraft[] {
  return Array.from({ length: size / 2 }, () => ({ homeTeamId: '', awayTeamId: '' }))
}

export function pairingsFromSuggestion(suggestion: KnockoutSuggestion): PairingDraft[] {
  return suggestion.pairings.map((pairing) => ({
    homeTeamId: String(pairing.home.teamId),
    awayTeamId: String(pairing.away.teamId),
  }))
}

export interface PairingValidation {
  /** One message per pairing, `null` when that pairing is fine. */
  errors: (string | null)[]
  valid: boolean
}

/** Every slot filled, no team against itself and no team used twice (mirrors the server, which answers 400). */
export function validatePairings(pairings: readonly PairingDraft[]): PairingValidation {
  const uses = new Map<string, number>()
  for (const pairing of pairings) {
    for (const id of [pairing.homeTeamId, pairing.awayTeamId]) {
      if (id) uses.set(id, (uses.get(id) ?? 0) + 1)
    }
  }
  const errors = pairings.map((pairing) => {
    if (!pairing.homeTeamId || !pairing.awayTeamId) return 'Elija los dos equipos del cruce.'
    if (pairing.homeTeamId === pairing.awayTeamId) return 'Un equipo no puede jugar contra sí mismo.'
    if ((uses.get(pairing.homeTeamId) ?? 0) > 1 || (uses.get(pairing.awayTeamId) ?? 0) > 1) {
      return 'Un equipo aparece en más de un cruce.'
    }
    return null
  })
  return { errors, valid: errors.every((error) => error === null) }
}

/** Options for one slot: every approved team not already used in another slot (the slot's own value stays). */
export function pairingTeamOptions(
  teams: readonly TeamOption[],
  pairings: readonly PairingDraft[],
  index: number,
  side: keyof PairingDraft,
): SelectOption[] {
  const taken = new Set<string>()
  pairings.forEach((pairing, position) => {
    for (const key of ['homeTeamId', 'awayTeamId'] as const) {
      if (position === index && key === side) continue
      if (pairing[key]) taken.add(pairing[key])
    }
  })
  return teams
    .filter((team) => !taken.has(String(team.id)))
    .map((team) => ({ value: String(team.id), label: team.name }))
}

export function toCreateKnockoutRequest(pairings: readonly PairingDraft[]): CreateKnockoutRequest {
  return {
    pairings: pairings.map((pairing) => ({
      homeTeamId: Number(pairing.homeTeamId),
      awayTeamId: Number(pairing.awayTeamId),
    })),
  }
}

/** How the winners of the first round meet next (winner of the first pairing against the winner of the last one, ...). */
export function nextRoundHint(pairingCount: number): string | null {
  if (pairingCount === 2) return 'Los ganadores de los dos cruces juegan la final.'
  if (pairingCount === 4) return 'En semifinales se cruzan los ganadores de los cruces 1 y 4, y los de los cruces 2 y 3.'
  return null
}
