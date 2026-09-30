import { useState } from 'react'
import { NavLink } from 'react-router'
import { Avatar } from '@/components/atoms/Avatar'
import { Button } from '@/components/atoms/Button'
import { cn } from '@/lib/cn'
import { ROLE_LABELS } from '@/lib/labels'
import type { Role, UserResponse } from '@/types/api'

export interface NavItem {
  to: string
  label: string
  /** Roles allowed to see the link. Empty means any authenticated user. */
  roles?: Role[]
  /** When true the user must literally hold one of `roles` (ADMIN does not imply them). */
  exactRoles?: boolean
  end?: boolean
}

export interface NavbarProps {
  user: UserResponse | null
  /** Role check resolved by the container (ADMIN implies all). */
  hasRole: (...roles: Role[]) => boolean
  /** Literal role check for personal-scope items (no ADMIN implication). Falls back to `hasRole`. */
  hasExactRole?: (...roles: Role[]) => boolean
  items: NavItem[]
  onLogout: () => void
  loggingOut?: boolean
}

export function isNavItemVisible(
  item: NavItem,
  hasRole: (...roles: Role[]) => boolean,
  hasExactRole: (...roles: Role[]) => boolean = hasRole,
): boolean {
  if (!item.roles || item.roles.length === 0) return true
  return item.exactRoles ? hasExactRole(...item.roles) : hasRole(...item.roles)
}

function Brand() {
  return (
    <NavLink to="/" className="flex items-center gap-2.5 text-white">
      {/* The logo is drawn with black outlines, so it sits on a white tile to stay legible on the dark header. */}
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-white p-1">
        <img src="/logo.svg" alt="" className="h-full w-auto" />
      </span>
      <span className="text-base font-semibold tracking-tight">TechCup Fútbol</span>
    </NavLink>
  )
}

function linkClasses(isActive: boolean, mobile = false): string {
  return cn(
    'rounded-lg text-sm font-medium transition-colors',
    mobile ? 'block px-3 py-2' : 'px-3 py-1.5',
    isActive ? 'bg-brand-600 text-white' : 'text-stone-300 hover:bg-white/10 hover:text-white',
  )
}

/** Role-aware top navigation. Presentational: receives the user and the visible items. */
export function Navbar({ user, hasRole, hasExactRole, items, onLogout, loggingOut }: NavbarProps) {
  const [open, setOpen] = useState(false)
  const visible = items.filter((item) => isNavItemVisible(item, hasRole, hasExactRole))

  return (
    <header className="sticky top-0 z-40 border-b border-stone-800 bg-ink text-white">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-4 sm:px-6">
        <Brand />

        <nav className="hidden items-center gap-1 md:flex" aria-label="Principal">
          {visible.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => linkClasses(isActive)}>
              {item.label}
            </NavLink>
          ))}
        </nav>

        <div className="hidden items-center gap-3 md:flex">
          {user && (
            <div className="flex items-center gap-2">
              <Avatar name={user.fullName} size="sm" />
              <div className="leading-tight">
                <p className="text-sm font-medium text-white">{user.fullName}</p>
                <p className="text-xs text-stone-400">{user.roles.map((role) => ROLE_LABELS[role]).join(' · ')}</p>
              </div>
            </div>
          )}
          <Button variant="secondary" size="sm" onClick={onLogout} loading={loggingOut}>
            Cerrar sesión
          </Button>
        </div>

        <button
          type="button"
          className="rounded-lg p-2 text-stone-300 hover:bg-white/10 hover:text-white md:hidden"
          aria-label={open ? 'Cerrar menú' : 'Abrir menú'}
          aria-expanded={open}
          onClick={() => setOpen((value) => !value)}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-6 w-6" aria-hidden="true">
            {open ? <path d="M6 6l12 12M6 18L18 6" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
          </svg>
        </button>
      </div>

      {open && (
        <div className="border-t border-stone-800 bg-ink px-4 py-3 md:hidden">
          {user && (
            <div className="mb-3 flex items-center gap-3 px-1">
              <Avatar name={user.fullName} size="md" />
              <div className="leading-tight">
                <p className="text-sm font-medium text-white">{user.fullName}</p>
                <p className="text-xs text-stone-400">{user.roles.map((role) => ROLE_LABELS[role]).join(' · ')}</p>
              </div>
            </div>
          )}
          <nav className="flex flex-col gap-1" aria-label="Principal (móvil)">
            {visible.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                onClick={() => setOpen(false)}
                className={({ isActive }) => linkClasses(isActive, true)}
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
          <div className="mt-3 border-t border-stone-800 pt-3">
            <Button variant="secondary" size="sm" fullWidth onClick={onLogout} loading={loggingOut}>
              Cerrar sesión
            </Button>
          </div>
        </div>
      )}
    </header>
  )
}
