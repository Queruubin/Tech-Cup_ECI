import type { NavItem } from '@/components/organisms/Navbar'

/**
 * Navigation entries with the roles allowed to see them. Empty roles = any authenticated user.
 * `exactRoles` marks personal-scope entries that ADMIN must hold literally (ADMIN implies management
 * roles, not a player's team, requests or a referee's assignments).
 */
export const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Inicio', end: true },
  { to: '/tournaments', label: 'Torneos' },
  { to: '/teams', label: 'Equipos' },
  { to: '/profile', label: 'Mi perfil' },
  { to: '/my-requests', label: 'Mis solicitudes', roles: ['PLAYER'], exactRoles: true },
  { to: '/my-team', label: 'Mi equipo', roles: ['CAPTAIN'], exactRoles: true },
  { to: '/players', label: 'Jugadores', roles: ['CAPTAIN', 'ORGANIZER'] },
  { to: '/referee/matches', label: 'Arbitraje', roles: ['REFEREE'], exactRoles: true },
  { to: '/admin/users', label: 'Usuarios', roles: ['ORGANIZER', 'ADMIN'] },
  { to: '/admin/audit', label: 'Auditoría', roles: ['ADMIN'] },
]
