import { useState } from 'react'
import { NavLink } from 'react-router'
import { Avatar } from '@/components/atoms/Avatar'
import { Button } from '@/components/atoms/Button'
import { cn } from '@/lib/cn'
import { summarizeRoles } from '@/lib/labels'
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

/**
 * Name and roles of the signed-in user. Long names are cut with an ellipsis and many roles collapse
 * into "main role +N"; the full text stays available in the tooltip.
 */
function UserIdentity({ user, avatarSize, className }: { user: UserResponse; avatarSize: 'sm' | 'md'; className?: string }) {
  const roles = summarizeRoles(user.roles)
  return (
    <div className={cn('flex min-w-0 items-center', avatarSize === 'md' ? 'gap-3' : 'gap-2', className)} title={`${user.fullName}\n${roles.full}`}>
      <Avatar name={user.fullName} size={avatarSize} />
      <div className="min-w-0 leading-tight">
        <p className="max-w-[9rem] truncate text-sm font-medium text-white xl:max-w-[12rem]" data-testid="navbar-user-name">
          {user.fullName}
        </p>
        {roles.primary && (
          <p className="flex items-center gap-1 text-xs text-stone-400" aria-label={`Roles: ${roles.full}`}>
            <span className="max-w-[7rem] truncate">{roles.primary}</span>
            {roles.extra > 0 && (
              <span className="shrink-0 rounded-full bg-white/10 px-1.5 text-[0.65rem] font-medium text-stone-200">
                +{roles.extra}
              </span>
            )}
          </p>
        )}
      </div>
    </div>
  )
}

function Brand() {
  return (
    <NavLink to="/" className="flex items-center gap-2.5 text-white">
      {/* The logo is drawn with black outlines, so it sits on a white tile to stay legible on the dark header. */}
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-white p-1">
        <img src="/logo.svg" alt="" className="h-full w-auto" />
      </span>
      <span className="whitespace-nowrap text-base font-semibold tracking-tight">TechCup Fútbol</span>
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

        {/* Full navigation from lg up: below that the links, the identity and the logout button do not fit. */}
        <nav className="hidden min-w-0 items-center gap-1 lg:flex" aria-label="Principal">
          {visible.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => linkClasses(isActive)}>
              {item.label}
            </NavLink>
          ))}
        </nav>

        <div className="hidden min-w-0 shrink-0 items-center gap-3 lg:flex">
          {user && <UserIdentity user={user} avatarSize="sm" />}
          <Button variant="secondary" size="sm" onClick={onLogout} loading={loggingOut}>
            Cerrar sesión
          </Button>
        </div>

        <button
          type="button"
          className="rounded-lg p-2 text-stone-300 hover:bg-white/10 hover:text-white lg:hidden"
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
        <div className="border-t border-stone-800 bg-ink px-4 py-3 lg:hidden">
          {user && <UserIdentity user={user} avatarSize="md" className="mb-3 px-1" />}
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
