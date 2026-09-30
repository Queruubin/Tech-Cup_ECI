import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { UserResponse } from '@/types/api'
import { SESSION_EXPIRED_KEY, SESSION_RETURN_TO_KEY } from '@/lib/session'
import { AUTH_STORAGE_KEY, handleUnauthorized, useAuthStore, userHasExactRole, userHasRole } from './auth.store'

const baseUser: UserResponse = {
  id: 7,
  fullName: 'Ana Pérez',
  email: 'ana.perez@escuelaing.edu.co',
  schoolRelation: 'STUDENT',
  academicProgram: 'SYSTEMS_ENGINEERING',
  semester: 5,
  status: 'ACTIVE',
  birthDate: '2003-04-10',
  documentType: 'CC',
  documentNumber: '1001',
  roles: ['PLAYER'],
  hasProfile: true,
  teamId: null,
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('auth.store', () => {
  beforeEach(() => {
    localStorage.clear()
    useAuthStore.setState({ token: null, expiresAt: null, user: null, loading: false })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('hasRole', () => {
    it('returns false without a session', () => {
      expect(useAuthStore.getState().hasRole('PLAYER')).toBe(false)
    })

    it('matches any of the requested roles', () => {
      useAuthStore.setState({ token: 't', user: { ...baseUser, roles: ['PLAYER', 'CAPTAIN'] } })
      const { hasRole } = useAuthStore.getState()
      expect(hasRole('CAPTAIN')).toBe(true)
      expect(hasRole('ORGANIZER', 'PLAYER')).toBe(true)
      expect(hasRole('ORGANIZER')).toBe(false)
      expect(hasRole('ADMIN')).toBe(false)
    })

    it('treats ADMIN as implying every role', () => {
      useAuthStore.setState({ token: 't', user: { ...baseUser, roles: ['ADMIN'] } })
      const { hasRole } = useAuthStore.getState()
      expect(hasRole('PLAYER')).toBe(true)
      expect(hasRole('ORGANIZER')).toBe(true)
      expect(hasRole('REFEREE', 'CAPTAIN')).toBe(true)
    })

    it('returns true for an empty role list when authenticated', () => {
      expect(userHasRole(baseUser, [])).toBe(true)
      expect(userHasRole(null, [])).toBe(false)
    })

    it('hasExactRole does not let ADMIN imply personal-scope roles', () => {
      expect(userHasExactRole({ ...baseUser, roles: ['ADMIN'] }, ['PLAYER'])).toBe(false)
      expect(userHasExactRole({ ...baseUser, roles: ['ADMIN'] }, ['ADMIN'])).toBe(true)
      expect(userHasExactRole({ ...baseUser, roles: ['ADMIN', 'CAPTAIN'] }, ['CAPTAIN'])).toBe(true)
      expect(userHasExactRole({ ...baseUser, roles: ['PLAYER'] }, ['CAPTAIN', 'PLAYER'])).toBe(true)
      expect(userHasExactRole(null, ['PLAYER'])).toBe(false)

      useAuthStore.setState({ token: 't', user: { ...baseUser, roles: ['ADMIN'] } })
      expect(useAuthStore.getState().hasRole('PLAYER')).toBe(true)
      expect(useAuthStore.getState().hasExactRole('PLAYER')).toBe(false)
    })
  })

  describe('handleUnauthorized', () => {
    beforeEach(() => {
      sessionStorage.clear()
      window.history.replaceState(null, '', '/tournaments/5?tab=matches')
    })

    it('clears the session, stores the expiry marker with the return path and redirects to /login', () => {
      useAuthStore.setState({ token: 'jwt-123', expiresAt: 'x', user: baseUser })
      const redirect = vi.fn()

      handleUnauthorized(redirect)

      expect(useAuthStore.getState().token).toBeNull()
      expect(useAuthStore.getState().user).toBeNull()
      expect(sessionStorage.getItem(SESSION_EXPIRED_KEY)).toBe('1')
      expect(sessionStorage.getItem(SESSION_RETURN_TO_KEY)).toBe('/tournaments/5?tab=matches')
      expect(redirect).toHaveBeenCalledWith('/login')
    })

    it('does nothing without a session (e.g. a failed login attempt)', () => {
      const redirect = vi.fn()
      handleUnauthorized(redirect)
      expect(redirect).not.toHaveBeenCalled()
      expect(sessionStorage.getItem(SESSION_EXPIRED_KEY)).toBeNull()
    })

    it('does not redirect again when already on /login', () => {
      window.history.replaceState(null, '', '/login')
      useAuthStore.setState({ token: 'jwt-123', expiresAt: 'x', user: baseUser })
      const redirect = vi.fn()
      handleUnauthorized(redirect)
      expect(useAuthStore.getState().token).toBeNull()
      expect(redirect).not.toHaveBeenCalled()
    })
  })

  describe('login', () => {
    it('stores token and user from the login response', async () => {
      const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        jsonResponse({ token: 'jwt-123', expiresAt: '2030-01-01T00:00:00Z', user: baseUser }),
      )
      const user = await useAuthStore.getState().login('ana.perez@escuelaing.edu.co', 'secret123')
      expect(user.id).toBe(7)
      const state = useAuthStore.getState()
      expect(state.token).toBe('jwt-123')
      expect(state.user?.fullName).toBe('Ana Pérez')
      expect(state.loading).toBe(false)
      const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
      expect(url).toBe('/api/auth/login')
      expect(init.method).toBe('POST')
    })

    it('resets loading and rethrows on failure', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        jsonResponse({ status: 401, error: 'Unauthorized', message: 'Credenciales inválidas', path: '/api/auth/login' }, 401),
      )
      await expect(useAuthStore.getState().login('x@y.co', 'bad')).rejects.toThrow('Credenciales inválidas')
      expect(useAuthStore.getState().loading).toBe(false)
      expect(useAuthStore.getState().token).toBeNull()
    })
  })

  describe('logout', () => {
    it('calls POST /auth/logout with the bearer token and clears the session', async () => {
      useAuthStore.setState({ token: 'jwt-123', expiresAt: 'x', user: baseUser })
      const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 204 }))

      await useAuthStore.getState().logout()

      const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
      expect(url).toBe('/api/auth/logout')
      expect(init.method).toBe('POST')
      expect(new Headers(init.headers).get('Authorization')).toBe('Bearer jwt-123')

      const state = useAuthStore.getState()
      expect(state.token).toBeNull()
      expect(state.user).toBeNull()
      expect(state.isAuthenticated()).toBe(false)
      const persisted = JSON.parse(localStorage.getItem(AUTH_STORAGE_KEY) ?? '{}') as { state?: { token?: string | null } }
      expect(persisted.state?.token ?? null).toBeNull()
    })

    it('clears the session even when the logout request fails', async () => {
      useAuthStore.setState({ token: 'jwt-123', expiresAt: 'x', user: baseUser })
      vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('network down'))
      await useAuthStore.getState().logout()
      expect(useAuthStore.getState().token).toBeNull()
      expect(useAuthStore.getState().user).toBeNull()
    })
  })
})
