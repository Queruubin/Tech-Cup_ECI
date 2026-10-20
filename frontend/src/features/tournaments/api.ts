import { api } from '@/lib/api'
import type {
  BracketResponse,
  CreateKnockoutRequest,
  CreateMatchRequest,
  CreateTournamentRequest,
  GroupResponse,
  GroupStandings,
  KnockoutSize,
  KnockoutSuggestion,
  MatchPhase,
  MatchResponse,
  RegistrationResponse,
  ReviewRegistrationRequest,
  StandingRow,
  TopScorer,
  TournamentResponse,
  UndoPhaseResponse,
  UpdateTournamentRequest,
  VenueResponse,
} from '@/types/api'

export const tournamentsApi = {
  list: (signal?: AbortSignal) => api.get<TournamentResponse[]>('/tournaments', undefined, signal),
  current: (signal?: AbortSignal) => api.get<TournamentResponse>('/tournaments/current', undefined, signal),
  get: (id: number, signal?: AbortSignal) => api.get<TournamentResponse>(`/tournaments/${id}`, undefined, signal),
  create: (payload: CreateTournamentRequest) => api.post<TournamentResponse>('/tournaments', payload),
  update: (id: number, payload: UpdateTournamentRequest) => api.patch<TournamentResponse>(`/tournaments/${id}`, payload),
  remove: (id: number) => api.delete<void>(`/tournaments/${id}`),
  activate: (id: number) => api.post<TournamentResponse>(`/tournaments/${id}/activate`),
  start: (id: number) => api.post<TournamentResponse>(`/tournaments/${id}/start`),
  finish: (id: number) => api.post<TournamentResponse>(`/tournaments/${id}/finish`),
  /** ADMIN or ORGANIZER: DRAFT/ACTIVE/IN_PROGRESS -> SUSPENDED (hidden and frozen). */
  suspend: (id: number) => api.post<TournamentResponse>(`/tournaments/${id}/suspend`),
  /** ADMIN or ORGANIZER: SUSPENDED -> the status it was suspended from. */
  resume: (id: number) => api.post<TournamentResponse>(`/tournaments/${id}/resume`),
  /** ADMIN only: deletes the tournament in any status with everything it owns (400 when the name differs). */
  hardDelete: (id: number, confirmName: string) => api.post<void>(`/tournaments/${id}/hard-delete`, { confirmName }),
  uploadRulebook: (id: number, file: File) => api.upload<TournamentResponse>(`/tournaments/${id}/rulebook`, file),

  createVenue: (id: number, payload: { name: string; description: string; file: File | null }) =>
    api.upload<VenueResponse>(`/tournaments/${id}/venues`, payload.file, {
      name: payload.name,
      description: payload.description,
    }),
  deleteVenue: (id: number, venueId: number) => api.delete<void>(`/tournaments/${id}/venues/${venueId}`),

  createRegistration: (id: number, receipt: File) =>
    api.upload<RegistrationResponse>(`/tournaments/${id}/registrations`, receipt),
  listRegistrations: (id: number, signal?: AbortSignal) =>
    api.get<RegistrationResponse[]>(`/tournaments/${id}/registrations`, undefined, signal),
  myRegistration: (id: number, signal?: AbortSignal) =>
    api.get<RegistrationResponse>(`/tournaments/${id}/registrations/mine`, undefined, signal),
  approveRegistration: (registrationId: number, payload: ReviewRegistrationRequest) =>
    api.post<RegistrationResponse>(`/registrations/${registrationId}/approve`, payload),
  rejectRegistration: (registrationId: number, payload: ReviewRegistrationRequest) =>
    api.post<RegistrationResponse>(`/registrations/${registrationId}/reject`, payload),
  cancelRegistration: (registrationId: number) => api.post<RegistrationResponse>(`/registrations/${registrationId}/cancel`),
  /** Organizer: removes an approved team from a tournament that has not started (409 otherwise). */
  removeRegistration: (registrationId: number) => api.delete<void>(`/registrations/${registrationId}`),

  /** Public: groups with their teams (404 for a suspended tournament unless staff). */
  groups: (id: number, signal?: AbortSignal) => api.get<GroupResponse[]>(`/tournaments/${id}/groups`, undefined, signal),
  /** Organizer: creates the next group ("Grupo A", "Grupo B", ...). */
  createGroup: (id: number) => api.post<GroupResponse>(`/tournaments/${id}/groups`),
  /** Organizer: deletes a group with no team and no match. */
  deleteGroup: (id: number, groupId: number) => api.delete<void>(`/tournaments/${id}/groups/${groupId}`),
  /** Organizer: puts an approved team in the group (409 when it already plays a match in its group). */
  assignTeam: (id: number, groupId: number, teamId: number) =>
    api.put<GroupResponse>(`/tournaments/${id}/groups/${groupId}/teams/${teamId}`),
  removeTeam: (id: number, groupId: number, teamId: number) =>
    api.delete<GroupResponse>(`/tournaments/${id}/groups/${groupId}/teams/${teamId}`),
  /** Organizer: creates the pairings the group is still missing (all against all). */
  groupRoundRobin: (id: number, groupId: number) =>
    api.post<MatchResponse[]>(`/tournaments/${id}/groups/${groupId}/round-robin`),
  /** Organizer: one group match created by hand. */
  createMatch: (id: number, payload: CreateMatchRequest) => api.post<MatchResponse>(`/tournaments/${id}/matches`, payload),
  knockoutSuggestion: (id: number, size: KnockoutSize, signal?: AbortSignal) =>
    api.get<KnockoutSuggestion>(`/tournaments/${id}/knockout/suggestion`, { size }, signal),
  /** Organizer: creates the first knockout round from the confirmed pairings (bracket order). */
  createKnockout: (id: number, payload: CreateKnockoutRequest) =>
    api.post<MatchResponse[]>(`/tournaments/${id}/knockout`, payload),
  /** Draws the next knockout round by hand (normally automatic; useful after undoing it). */
  advanceMatches: (id: number) => api.post<MatchResponse[]>(`/tournaments/${id}/matches/advance`),
  /** Deletes every match of the latest phase (409 when any of them was played). */
  undoPhase: (id: number) => api.post<UndoPhaseResponse>(`/tournaments/${id}/matches/undo-phase`),
  matches: (id: number, phase?: MatchPhase | '', signal?: AbortSignal) =>
    api.get<MatchResponse[]>(`/tournaments/${id}/matches`, { phase: phase || undefined }, signal),
  standings: (id: number, signal?: AbortSignal) => api.get<StandingRow[]>(`/tournaments/${id}/standings`, undefined, signal),
  groupStandings: (id: number, signal?: AbortSignal) =>
    api.get<GroupStandings[]>(`/tournaments/${id}/standings/groups`, undefined, signal),
  bracket: (id: number, signal?: AbortSignal) => api.get<BracketResponse>(`/tournaments/${id}/bracket`, undefined, signal),
  topScorers: (id: number, signal?: AbortSignal) => api.get<TopScorer[]>(`/tournaments/${id}/stats/top-scorers`, undefined, signal),
  history: (id: number, signal?: AbortSignal) => api.get<MatchResponse[]>(`/tournaments/${id}/stats/history`, undefined, signal),
  teamResults: (id: number, teamId: number, signal?: AbortSignal) =>
    api.get<MatchResponse[]>(`/tournaments/${id}/teams/${teamId}/results`, undefined, signal),
}
