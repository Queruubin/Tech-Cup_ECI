import { api } from '@/lib/api'
import type {
  CreateInvitationRequest,
  CreateTeamRequest,
  EligibilityResponse,
  JoinRequestResponse,
  JoinRequestStatus,
  TeamResponse,
  UpdateTeamRequest,
} from '@/types/api'

export const teamsApi = {
  list: (signal?: AbortSignal) => api.get<TeamResponse[]>('/teams', undefined, signal),
  mine: (signal?: AbortSignal) => api.get<TeamResponse>('/teams/mine', undefined, signal),
  get: (id: number, signal?: AbortSignal) => api.get<TeamResponse>(`/teams/${id}`, undefined, signal),
  create: (payload: CreateTeamRequest) => api.post<TeamResponse>('/teams', payload),
  update: (id: number, payload: UpdateTeamRequest) => api.patch<TeamResponse>(`/teams/${id}`, payload),
  removeMember: (id: number, userId: number) => api.delete<TeamResponse>(`/teams/${id}/members/${userId}`),
  /** The signed-in member leaves the team (not the captain, not while the team is locked). */
  leave: (id: number) => api.post<void>(`/teams/${id}/leave`),
  inactivate: (id: number) => api.post<TeamResponse>(`/teams/${id}/inactivate`),
  /** ADMIN only: deletes the team for good (409 while it plays a match or holds a live registration). */
  remove: (id: number) => api.delete<void>(`/teams/${id}`),
  eligibility: (id: number, signal?: AbortSignal) =>
    api.get<EligibilityResponse>(`/teams/${id}/eligibility`, undefined, signal),

  // Invitations (captain of the team or ADMIN).
  invite: (teamId: number, payload: CreateInvitationRequest) =>
    api.post<JoinRequestResponse>(`/teams/${teamId}/invitations`, payload),
  /** Newest first; `status` is optional. */
  invitations: (teamId: number, status?: JoinRequestStatus, signal?: AbortSignal) =>
    api.get<JoinRequestResponse[]>(`/teams/${teamId}/invitations`, { status }, signal),
  cancelInvitation: (id: number) => api.post<JoinRequestResponse>(`/invitations/${id}/cancel`),
}
