import { api } from '@/lib/api'
import type {
  BracketResponse,
  CreateTournamentRequest,
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

  generateMatches: (id: number) => api.post<MatchResponse[]>(`/tournaments/${id}/matches/generate`),
  advanceMatches: (id: number) => api.post<MatchResponse[]>(`/tournaments/${id}/matches/advance`),
  /** Deletes every match of the latest phase (409 when any of them was played). */
  undoPhase: (id: number) => api.post<UndoPhaseResponse>(`/tournaments/${id}/matches/undo-phase`),
  matches: (id: number, phase?: MatchPhase | '', signal?: AbortSignal) =>
    api.get<MatchResponse[]>(`/tournaments/${id}/matches`, { phase: phase || undefined }, signal),
  standings: (id: number, signal?: AbortSignal) => api.get<StandingRow[]>(`/tournaments/${id}/standings`, undefined, signal),
  bracket: (id: number, signal?: AbortSignal) => api.get<BracketResponse>(`/tournaments/${id}/bracket`, undefined, signal),
  topScorers: (id: number, signal?: AbortSignal) => api.get<TopScorer[]>(`/tournaments/${id}/stats/top-scorers`, undefined, signal),
  history: (id: number, signal?: AbortSignal) => api.get<MatchResponse[]>(`/tournaments/${id}/stats/history`, undefined, signal),
  teamResults: (id: number, teamId: number, signal?: AbortSignal) =>
    api.get<MatchResponse[]>(`/tournaments/${id}/teams/${teamId}/results`, undefined, signal),
}
