import { useAuthStore } from '@/store/auth.store'

/** Convenience selector over the auth store for containers and guards. */
export function useAuth() {
  const token = useAuthStore((state) => state.token)
  const user = useAuthStore((state) => state.user)
  const loading = useAuthStore((state) => state.loading)
  const login = useAuthStore((state) => state.login)
  const logout = useAuthStore((state) => state.logout)
  const refreshMe = useAuthStore((state) => state.refreshMe)
  const hasRole = useAuthStore((state) => state.hasRole)
  const hasExactRole = useAuthStore((state) => state.hasExactRole)

  return {
    token,
    user,
    loading,
    isAuthenticated: !!token && !!user,
    login,
    logout,
    refreshMe,
    hasRole,
    hasExactRole,
  }
}
