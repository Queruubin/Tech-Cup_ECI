import { beforeEach, describe, expect, it } from 'vitest'
import { SESSION_EXPIRED_KEY, SESSION_RETURN_TO_KEY, consumeSessionExpired, isSafeReturnPath, markSessionExpired } from './session'

describe('session expiry marker', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('stores the marker and the return path, then consumes both exactly once', () => {
    markSessionExpired('/tournaments/3?tab=matches')
    expect(sessionStorage.getItem(SESSION_EXPIRED_KEY)).toBe('1')
    expect(sessionStorage.getItem(SESSION_RETURN_TO_KEY)).toBe('/tournaments/3?tab=matches')

    expect(consumeSessionExpired()).toEqual({ expired: true, returnTo: '/tournaments/3?tab=matches' })
    expect(sessionStorage.getItem(SESSION_EXPIRED_KEY)).toBeNull()
    expect(sessionStorage.getItem(SESSION_RETURN_TO_KEY)).toBeNull()
    expect(consumeSessionExpired()).toEqual({ expired: false, returnTo: null })
  })

  it('reports nothing when no marker was stored', () => {
    expect(consumeSessionExpired()).toEqual({ expired: false, returnTo: null })
  })

  it('refuses unsafe return targets', () => {
    expect(isSafeReturnPath('/my-team')).toBe(true)
    expect(isSafeReturnPath('/login')).toBe(false)
    expect(isSafeReturnPath('//evil.example')).toBe(false)
    expect(isSafeReturnPath('https://evil.example')).toBe(false)
    expect(isSafeReturnPath(null)).toBe(false)

    markSessionExpired('//evil.example')
    expect(consumeSessionExpired()).toEqual({ expired: true, returnTo: null })
  })
})
