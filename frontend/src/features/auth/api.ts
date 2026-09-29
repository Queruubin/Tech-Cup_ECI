import { api } from '@/lib/api'
import type { ChangePasswordRequest, RegisterRequest, UserResponse } from '@/types/api'

export const authApi = {
  register: (payload: RegisterRequest) => api.post<UserResponse>('/auth/register', payload, { skipAuthRedirect: true }),
  me: () => api.get<UserResponse>('/auth/me'),
  /** Self-service password change; the backend answers 400 with a message when the current password is wrong. */
  changePassword: (payload: ChangePasswordRequest) => api.post<void>('/auth/password', payload),
}
