import { useState } from 'react'
import { Outlet, useNavigate } from 'react-router'
import { Navbar } from '@/components/organisms/Navbar'
import { AppShell } from '@/components/templates/AppShell'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { useSessionRefresh } from '@/features/auth/hooks/useSessionRefresh'
import { NAV_ITEMS } from './navigation'

/** Container for authenticated pages: wires the store to the presentational Navbar. */
export function AppLayout() {
  const { user, hasRole, hasExactRole, logout } = useAuth()
  const navigate = useNavigate()
  const [loggingOut, setLoggingOut] = useState(false)
  useSessionRefresh()

  const handleLogout = async () => {
    setLoggingOut(true)
    try {
      await logout()
    } finally {
      setLoggingOut(false)
      navigate('/login', { replace: true })
    }
  }

  return (
    <AppShell
      navbar={
        <Navbar
          user={user}
          hasRole={hasRole}
          hasExactRole={hasExactRole}
          items={NAV_ITEMS}
          onLogout={handleLogout}
          loggingOut={loggingOut}
        />
      }
    >
      <Outlet />
    </AppShell>
  )
}
