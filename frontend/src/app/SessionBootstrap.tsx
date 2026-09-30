import { useEffect, type ReactNode } from 'react'
import { useAuthStore } from '@/store/auth.store'

/**
 * On startup, revalidates a persisted session against `GET /auth/me`.
 * A 401 is handled by the API client (session cleared + redirect).
 * Later refreshes (route changes, tab visibility) live in `useSessionRefresh` within `AppLayout`.
 */
export function SessionBootstrap({ children }: { children: ReactNode }) {
  const token = useAuthStore((state) => state.token)
  const refreshMe = useAuthStore((state) => state.refreshMe)

  useEffect(() => {
    if (!token) return
    refreshMe().catch(() => {
      /* network errors keep the cached user; 401 is handled globally */
    })
    // Only on mount / when a new token is set.
  }, [token, refreshMe])

  return <>{children}</>
}
