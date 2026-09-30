import { act, render } from '@testing-library/react'
import { useEffect } from 'react'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth.store'
import { SESSION_REFRESH_DEBOUNCE_MS, useSessionRefresh } from './useSessionRefresh'

let navigateTo: ((path: string) => void) | undefined

function Shell() {
  useSessionRefresh()
  const navigate = useNavigate()
  useEffect(() => {
    navigateTo = navigate
  }, [navigate])
  return null
}

function renderShell() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="*" element={<Shell />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('useSessionRefresh', () => {
  const refreshMe = vi.fn(() => Promise.resolve(null))

  beforeEach(() => {
    vi.useFakeTimers()
    refreshMe.mockClear()
    useAuthStore.setState({ token: 'jwt', refreshMe })
  })

  afterEach(() => {
    vi.useRealTimers()
    useAuthStore.setState({ token: null, user: null })
  })

  it('does not refresh on mount (SessionBootstrap owns the initial load)', () => {
    renderShell()
    act(() => vi.advanceTimersByTime(SESSION_REFRESH_DEBOUNCE_MS * 2))
    expect(refreshMe).not.toHaveBeenCalled()
  })

  it('refreshes once after a burst of navigations (debounced)', () => {
    renderShell()
    act(() => navigateTo?.('/teams'))
    act(() => navigateTo?.('/teams/1'))
    act(() => navigateTo?.('/my-team'))
    expect(refreshMe).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(SESSION_REFRESH_DEBOUNCE_MS))
    expect(refreshMe).toHaveBeenCalledTimes(1)
  })

  it('refreshes when the tab becomes visible again and ignores failures', () => {
    refreshMe.mockImplementationOnce(() => Promise.reject(new Error('offline')))
    renderShell()
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(refreshMe).toHaveBeenCalledTimes(1)
  })

  it('does nothing without a session token', () => {
    useAuthStore.setState({ token: null })
    renderShell()
    act(() => navigateTo?.('/teams'))
    act(() => vi.advanceTimersByTime(SESSION_REFRESH_DEBOUNCE_MS))
    expect(refreshMe).not.toHaveBeenCalled()
  })
})
