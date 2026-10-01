import { useQuery } from '@/lib/useQuery'
import type { Position } from '@/types/api'
import { playersApi } from '../api'

/** Own sport profile. Resolves to `null` (not an error) when the profile does not exist yet. */
export function useMyProfile(enabled = true) {
  return useQuery((signal) => playersApi.getMyProfile(signal), [], { enabled, nullOnStatus: [404] })
}

export function useFreeAgents(position: Position | '') {
  return useQuery((signal) => playersApi.search({ position, available: true }, signal), [position])
}

export function useMyJoinRequests() {
  return useQuery((signal) => playersApi.getMyJoinRequests(signal), [])
}

export function useTeamJoinRequests(teamId: number | null) {
  return useQuery(
    (signal) => playersApi.getTeamJoinRequests(teamId as number, 'PENDING', signal),
    [teamId],
    { enabled: teamId !== null },
  )
}

/** Invitations received by the current player, every status. */
export function useMyInvitations(enabled = true) {
  return useQuery((signal) => playersApi.getMyInvitations(signal), [], { enabled })
}
