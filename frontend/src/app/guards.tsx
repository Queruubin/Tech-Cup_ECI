import type { ReactNode } from 'react'
import { Link, Navigate, Outlet, useLocation } from 'react-router'
import { Button } from '@/components/atoms/Button'
import { EmptyState } from '@/components/molecules/EmptyState'
import { useAuth } from '@/features/auth/hooks/useAuth'
import type { Role } from '@/types/api'

/** Redirects anonymous visitors to /login, remembering where they wanted to go. */
export function RequireAuth({ children }: { children?: ReactNode }) {
  const { isAuthenticated } = useAuth()
  const location = useLocation()
  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />
  }
  return children ? <>{children}</> : <Outlet />
}

export interface RequireRoleProps {
  roles: Role[]
  /**
   * Personal-scope pages ("Mi equipo", "Solicitudes", "Arbitraje"): the user must literally hold
   * one of the roles; ADMIN does not imply them. Management pages keep the default ADMIN implication.
   */
  exact?: boolean
  children?: ReactNode
}

/** Renders a 403 state when the user lacks all of the given roles (ADMIN implies every role unless `exact`). */
export function RequireRole({ roles, exact = false, children }: RequireRoleProps) {
  const { hasRole, hasExactRole } = useAuth()
  const allowed = exact ? hasExactRole(...roles) : hasRole(...roles)
  if (!allowed) {
    return (
      <EmptyState
        title="Sin permisos"
        description="Su cuenta no tiene el rol necesario para acceder a esta sección."
        action={
          <Link to="/">
            <Button size="sm" variant="outline">
              Volver al inicio
            </Button>
          </Link>
        }
      />
    )
  }
  return children ? <>{children}</> : <Outlet />
}
