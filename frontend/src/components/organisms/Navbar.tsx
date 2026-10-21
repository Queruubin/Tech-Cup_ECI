import { useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { NavLink } from 'react-router'
import { Avatar } from '@/components/atoms/Avatar'
import { Badge, StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { cn } from '@/lib/cn'
import { firstName } from '@/lib/format'
import { headerRoles } from '@/lib/labels'
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
 * How much of the desktop header fits (from `lg` up), richest first: everything; the links without the
 * role badges; then also the logo without its text and tighter links; finally only the menu button.
 * Links win over badges; the first name and the logout button always stay.
 */
type DesktopFit = 'full' | 'no-badges' | 'compact' | 'menu'
const NEXT_FIT: Record<DesktopFit, DesktopFit> = { full: 'no-badges', 'no-badges': 'compact', compact: 'menu', menu: 'menu' }

/**
 * Picks the richest `DesktopFit` whose links do not overflow their slot. Every time the header width,
 * the links or the roles change it starts again from `full` and steps down while the links overflow;
 * the steps run in layout effects, so only the final layout is painted. Without `ResizeObserver`
 * (tests) or below `lg` (the slot is not displayed, so nothing overflows) it stays `full`.
 */
function useDesktopFit(rowRef: RefObject<HTMLElement>, slotRef: RefObject<HTMLElement>, signature: string): DesktopFit {
  const [fit, setFit] = useState<DesktopFit>('full')
  const [generation, setGeneration] = useState(0)
  // New links or a new user: measure again from `full` (state reset during render, not in an effect).
  const [measuredFor, setMeasuredFor] = useState(signature)
  if (measuredFor !== signature) {
    setMeasuredFor(signature)
    setFit('full')
  }

  useLayoutEffect(() => {
    const row = rowRef.current
    if (!row || typeof ResizeObserver === 'undefined') return
    let width = row.clientWidth
    const observer = new ResizeObserver(() => {
      if (row.clientWidth === width) return
      width = row.clientWidth
      setFit('full')
      setGeneration((value) => value + 1)
    })
    observer.observe(row)
    return () => observer.disconnect()
  }, [rowRef])

  useLayoutEffect(() => {
    const slot = slotRef.current
    if (!slot || fit === 'menu') return
    if (slot.scrollWidth > slot.clientWidth + 1) setFit(NEXT_FIT[fit])
  }, [fit, generation, signature, slotRef])

  return fit
}

/** Avatar, first name and (when there is room) up to three role badges of the signed-in user. */
function UserIdentity({
  user,
  avatarSize,
  showRoles,
  wrapRoles = false,
  className,
}: {
  user: UserResponse
  avatarSize: 'sm' | 'md'
  showRoles: boolean
  wrapRoles?: boolean
  className?: string
}) {
  const roles = headerRoles(user.roles)
  return (
    <div
      className={cn('flex min-w-0 items-center', avatarSize === 'md' ? 'gap-3' : 'gap-2', className)}
      title={roles.full ? `${user.fullName}\n${roles.full}` : user.fullName}
      data-testid="navbar-identity"
    >
      <Avatar name={user.fullName} size={avatarSize} />
      <p className="min-w-0 max-w-[8rem] truncate text-sm font-medium text-white" data-testid="navbar-user-name">
        <span aria-hidden="true">{firstName(user.fullName)}</span>
        <span className="sr-only">{user.fullName}</span>
      </p>
      {showRoles && roles.visible.length > 0 && (
        <ul
          className={cn('flex min-w-0 items-center gap-1', wrapRoles ? 'flex-wrap' : 'shrink-0')}
          aria-label={`Roles: ${roles.full}`}
          data-testid="navbar-roles"
        >
          {roles.visible.map((role) => (
            <li key={role}>
              <StatusBadge kind="role" value={role} />
            </li>
          ))}
          {roles.overflow && (
            <li title={roles.full} aria-label={`Todos los roles: ${roles.full}`}>
              <Badge tone="neutral">…</Badge>
            </li>
          )}
        </ul>
      )}
    </div>
  )
}

function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <NavLink to="/" className="flex shrink-0 items-center gap-2.5 text-white">
      {/* The logo is drawn with black outlines, so it sits on a white tile to stay legible on the dark header. */}
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-white p-1">
        <img src="/logo.svg" alt="" className="h-full w-auto" />
      </span>
      {/* Compact header: the text stays for screen readers, the logo alone is shown. */}
      <span className={cn('whitespace-nowrap text-base font-semibold tracking-tight', compact && 'sr-only')}>TechCup Fútbol</span>
    </NavLink>
  )
}

function linkClasses(isActive: boolean, mobile = false, tight = false): string {
  return cn(
    'whitespace-nowrap rounded-lg text-sm font-medium transition-colors',
    mobile ? 'block px-3 py-2' : tight ? 'px-2 py-1.5' : 'px-3 py-1.5',
    isActive ? 'bg-brand-600 text-white' : 'text-stone-300 hover:bg-white/10 hover:text-white',
  )
}

/** Role-aware top navigation. Presentational: receives the user and the visible items. */
export function Navbar({ user, hasRole, hasExactRole, items, onLogout, loggingOut }: NavbarProps) {
  const [open, setOpen] = useState(false)
  const visible = items.filter((item) => isNavItemVisible(item, hasRole, hasExactRole))
  const rowRef = useRef<HTMLDivElement>(null)
  const slotRef = useRef<HTMLDivElement>(null)
  const fit = useDesktopFit(rowRef, slotRef, `${visible.map((item) => item.to).join('|')}#${user?.fullName ?? ''}#${user?.roles.join(',') ?? ''}`)
  // From lg up the links live in the header unless they do not fit; then the menu button takes over.
  const desktopMenu = fit === 'menu'
  const compact = fit === 'compact'

  return (
    <header className="sticky top-0 z-40 border-b border-stone-800 bg-ink text-white">
      <div ref={rowRef} className="mx-auto flex h-16 max-w-6xl items-center gap-4 px-4 sm:px-6">
        <Brand compact={compact} />

        {/* The slot takes the free width; the links never shrink, so an overflow means they do not fit. */}
        <div ref={slotRef} className="hidden min-w-0 flex-1 overflow-hidden lg:block" data-testid="navbar-links-slot">
          {!desktopMenu && (
            <nav className="flex w-max items-center gap-1" aria-label="Principal">
              {visible.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) => linkClasses(isActive, false, fit === 'compact')}
                >
                  {item.label}
                </NavLink>
              ))}
            </nav>
          )}
        </div>
        {/* Below lg the slot is hidden: this spacer keeps the menu button on the right. */}
        <div className="flex-1 lg:hidden" aria-hidden="true" />

        <div className="hidden shrink-0 items-center gap-3 lg:flex">
          {user && <UserIdentity user={user} avatarSize="sm" showRoles={fit === 'full'} />}
          <Button variant="secondary" size="sm" onClick={onLogout} loading={loggingOut}>
            Cerrar sesión
          </Button>
        </div>

        <button
          type="button"
          className={cn(
            'shrink-0 rounded-lg p-2 text-stone-300 hover:bg-white/10 hover:text-white',
            !desktopMenu && 'lg:hidden',
          )}
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
        <div className={cn('border-t border-stone-800 bg-ink px-4 py-3', !desktopMenu && 'lg:hidden')}>
          <div className="mx-auto max-w-6xl">
            {user && (
              <UserIdentity user={user} avatarSize="md" showRoles wrapRoles className="mb-3 flex-wrap px-1 lg:hidden" />
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
            <div className="mt-3 border-t border-stone-800 pt-3 lg:hidden">
              <Button variant="secondary" size="sm" fullWidth onClick={onLogout} loading={loggingOut}>
                Cerrar sesión
              </Button>
            </div>
          </div>
        </div>
      )}
    </header>
  )
}
