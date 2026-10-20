import { useQuery } from '@/lib/useQuery'
import type { MatchPhase } from '@/types/api'
import { tournamentsApi } from '../api'

export function useTournaments() {
  return useQuery((signal) => tournamentsApi.list(signal), [])
}

/** Latest ACTIVE or IN_PROGRESS tournament; `null` when the backend answers 404. */
export function useCurrentTournament() {
  return useQuery((signal) => tournamentsApi.current(signal), [], { nullOnStatus: [404] })
}

export function useTournament(id: number | null) {
  return useQuery((signal) => tournamentsApi.get(id as number, signal), [id], { enabled: id !== null })
}

export function useStandings(id: number | null, enabled = true) {
  return useQuery((signal) => tournamentsApi.standings(id as number, signal), [id], { enabled: id !== null && enabled })
}

/** One table per group (empty when the tournament has no groups). */
export function useGroupStandings(id: number | null, enabled = true) {
  return useQuery((signal) => tournamentsApi.groupStandings(id as number, signal), [id], {
    enabled: id !== null && enabled,
  })
}

export function useGroups(id: number | null, enabled = true) {
  return useQuery((signal) => tournamentsApi.groups(id as number, signal), [id], { enabled: id !== null && enabled })
}

export function useBracket(id: number | null, enabled = true) {
  return useQuery((signal) => tournamentsApi.bracket(id as number, signal), [id], { enabled: id !== null && enabled })
}

export function useTournamentMatches(id: number | null, phase: MatchPhase | '' = '', enabled = true) {
  return useQuery((signal) => tournamentsApi.matches(id as number, phase, signal), [id, phase], {
    enabled: id !== null && enabled,
  })
}

export function useTopScorers(id: number | null, enabled = true) {
  return useQuery((signal) => tournamentsApi.topScorers(id as number, signal), [id], { enabled: id !== null && enabled })
}

export function useMatchHistory(id: number | null, enabled = true) {
  return useQuery((signal) => tournamentsApi.history(id as number, signal), [id], { enabled: id !== null && enabled })
}

export function useTeamResults(id: number | null, teamId: number | null) {
  return useQuery((signal) => tournamentsApi.teamResults(id as number, teamId as number, signal), [id, teamId], {
    enabled: id !== null && teamId !== null,
  })
}

export function useRegistrations(id: number | null, enabled = true) {
  return useQuery((signal) => tournamentsApi.listRegistrations(id as number, signal), [id], {
    enabled: id !== null && enabled,
  })
}

/** The captain's registration for this tournament; `null` on 404. */
export function useMyRegistration(id: number | null, enabled = true) {
  return useQuery((signal) => tournamentsApi.myRegistration(id as number, signal), [id], {
    enabled: id !== null && enabled,
    nullOnStatus: [404],
  })
}
