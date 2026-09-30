import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router'
import { useAuthStore } from '@/store/auth.store'

/** Delay before `GET /auth/me` fires after a navigation, so rapid route changes coalesce into one call. */
export const SESSION_REFRESH_DEBOUNCE_MS = 400

/**
 * Keeps `auth.store.user` (roles, teamId, hasProfile) fresh while the app is open:
 * - on every route change (debounced), so a player accepted into a team or granted CAPTAIN sees the
 *   new nav items and permissions without re-login;
 * - when the tab becomes visible again.
 * Failures are ignored: a 401 is handled globally by the API client, network errors keep the cache.
 * The initial load is owned by `SessionBootstrap`, so the first render is skipped here.
 */
export function useSessionRefresh(): void {
  const location = useLocation()
  const token = useAuthStore((state) => state.token)
  const refreshMe = useAuthStore((state) => state.refreshMe)
  const firstRender = useRef(true)

  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    if (!token) return
    const timer = window.setTimeout(() => {
      refreshMe().catch(() => undefined)
    }, SESSION_REFRESH_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
    // Only the path matters: query/hash changes do not alter the session.
  }, [location.pathname, token, refreshMe])

  useEffect(() => {
    if (!token) return
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') refreshMe().catch(() => undefined)
    }
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => document.removeEventListener('visibilitychange', onVisibilityChange)
  }, [token, refreshMe])
}
