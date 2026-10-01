import { api } from '@/lib/api'
import type {
  CreateJoinRequest,
  JoinRequestResponse,
  JoinRequestStatus,
  PlayerProfileResponse,
  Position,
  UpdateUserRequest,
  UpsertProfileRequest,
  UserResponse,
} from '@/types/api'

export const playersApi = {
  getMyProfile: (signal?: AbortSignal) => api.get<PlayerProfileResponse>('/players/me/profile', undefined, signal),
  upsertMyProfile: (payload: UpsertProfileRequest) => api.put<PlayerProfileResponse>('/players/me/profile', payload),
  uploadMyPhoto: (file: File) => api.upload<PlayerProfileResponse>('/players/me/profile/photo', file),
  getProfile: (userId: number, signal?: AbortSignal) =>
    api.get<PlayerProfileResponse>(`/players/${userId}/profile`, undefined, signal),
  search: (filters: { position?: Position | ''; available?: boolean }, signal?: AbortSignal) =>
    api.get<PlayerProfileResponse[]>(
      '/players',
      { position: filters.position || undefined, available: filters.available ?? true },
      signal,
    ),

  updateUser: (userId: number, payload: UpdateUserRequest) => api.patch<UserResponse>(`/users/${userId}`, payload),

  createJoinRequest: (teamId: number, payload: CreateJoinRequest) =>
    api.post<JoinRequestResponse>(`/teams/${teamId}/join-requests`, payload),
  getMyJoinRequests: (signal?: AbortSignal) =>
    api.get<JoinRequestResponse[]>('/players/me/join-requests', undefined, signal),
  cancelJoinRequest: (id: number) => api.post<JoinRequestResponse>(`/join-requests/${id}/cancel`),
  getTeamJoinRequests: (teamId: number, status?: JoinRequestStatus, signal?: AbortSignal) =>
    api.get<JoinRequestResponse[]>(`/teams/${teamId}/join-requests`, { status }, signal),
  acceptJoinRequest: (id: number) => api.post<JoinRequestResponse>(`/join-requests/${id}/accept`),
  rejectJoinRequest: (id: number) => api.post<JoinRequestResponse>(`/join-requests/${id}/reject`),

  // Invitations received by the current player (all statuses, newest first).
  getMyInvitations: (signal?: AbortSignal) =>
    api.get<JoinRequestResponse[]>('/players/me/invitations', undefined, signal),
  acceptInvitation: (id: number) => api.post<JoinRequestResponse>(`/invitations/${id}/accept`),
  rejectInvitation: (id: number) => api.post<JoinRequestResponse>(`/invitations/${id}/reject`),
}
