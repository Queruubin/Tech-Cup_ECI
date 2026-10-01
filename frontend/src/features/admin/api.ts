import { api } from '@/lib/api'
import type {
  AuditLogResponse,
  CreateRefereeRequest,
  ResetPasswordRequest,
  Role,
  UpdateUserRequest,
  UserResponse,
} from '@/types/api'

export const adminApi = {
  searchUsers: (search: string, signal?: AbortSignal) =>
    api.get<UserResponse[]>('/admin/users', { search: search || undefined }, signal),
  getUserRoles: (userId: number, signal?: AbortSignal) => api.get<Role[]>(`/admin/users/${userId}/roles`, undefined, signal),
  assignRole: (userId: number, role: Role) => api.post<Role[]>(`/admin/users/${userId}/roles`, { role }),
  removeRole: (userId: number, role: Role) => api.delete<Role[]>(`/admin/users/${userId}/roles/${role}`),
  inactivateUser: (userId: number) => api.post<UserResponse>(`/admin/users/${userId}/inactivate`),
  /** ADMIN only: sets a new password for the user without knowing the current one. */
  resetPassword: (userId: number, payload: ResetPasswordRequest) =>
    api.post<void>(`/admin/users/${userId}/password`, payload),
  /** ADMIN may edit every field of any user, including the school relation. */
  updateUser: (userId: number, payload: UpdateUserRequest) => api.patch<UserResponse>(`/users/${userId}`, payload),

  createReferee: (payload: CreateRefereeRequest) => api.post<UserResponse>('/organizer/referees', payload),
  listReferees: (signal?: AbortSignal) => api.get<UserResponse[]>('/organizer/referees', undefined, signal),

  audit: (filters: { action?: string; limit?: number }, signal?: AbortSignal) =>
    api.get<AuditLogResponse[]>('/admin/audit', { action: filters.action || undefined, limit: filters.limit }, signal),
}
