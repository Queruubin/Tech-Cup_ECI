import { api } from '@/lib/api'
import type {
  CancelReason,
  LineupResponse,
  MatchResponse,
  MatchResultRequest,
  SanctionedPlayer,
  UpdateMatchRequest,
  UpsertLineupRequest,
} from '@/types/api'

/** Competition endpoints (docs/ARCHITECTURE.md section 3.5, block COMPETITION). */
export const competitionApi = {
  getMatch: (id: number, signal?: AbortSignal) => api.get<MatchResponse>(`/matches/${id}`, undefined, signal),
  updateMatch: (id: number, payload: UpdateMatchRequest) => api.patch<MatchResponse>(`/matches/${id}`, payload),
  /**
   * Soft cancellation: the match keeps its record with status CANCELLED. Knockout matches require
   * `winnerTeamId` (walkover); the query helper drops it when undefined.
   */
  cancelMatch: (id: number, reason: CancelReason, winnerTeamId?: number) =>
    api.delete<MatchResponse>(`/matches/${id}`, { reason, winnerTeamId }),
  /** Records a first result or corrects an existing one (allowed while `resultEditable` is true). */
  recordResult: (id: number, payload: MatchResultRequest) => api.post<MatchResponse>(`/matches/${id}/result`, payload),
  /** PLAYED or CANCELLED → SCHEDULED; clears score, penalties, events, cancel reason and walkover. */
  reopenMatch: (id: number) => api.post<MatchResponse>(`/matches/${id}/reopen`),

  upsertLineup: (matchId: number, payload: UpsertLineupRequest) =>
    api.put<LineupResponse>(`/matches/${matchId}/lineups`, payload),
  getLineup: (matchId: number, teamId: number, signal?: AbortSignal) =>
    api.get<LineupResponse>(`/matches/${matchId}/lineups/${teamId}`, undefined, signal),

  refereeMatches: (signal?: AbortSignal) => api.get<MatchResponse[]>('/referees/me/matches', undefined, signal),
  sanctionedPlayers: (matchId: number, signal?: AbortSignal) =>
    api.get<SanctionedPlayer[]>(`/matches/${matchId}/sanctioned-players`, undefined, signal),
}
