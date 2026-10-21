import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { Navbar, isNavItemVisible } from '@/components/organisms/Navbar'
import { headerRoles } from '@/lib/labels'
import { userHasExactRole, userHasRole } from '@/store/auth.store'
import type { Role, UserResponse } from '@/types/api'
import { NAV_ITEMS } from './navigation'

function user(roles: Role[]): UserResponse {
  return {
    id: 1,
    fullName: 'Ana Pérez',
    email: 'ana@escuelaing.edu.co',
    schoolRelation: 'STUDENT',
    academicProgram: 'SYSTEMS_ENGINEERING',
    semester: 5,
    status: 'ACTIVE',
    birthDate: null,
    documentType: null,
    documentNumber: null,
    roles,
    hasProfile: false,
    teamId: null,
  }
}

function visibleLabels(roles: Role[]): string[] {
  const me = user(roles)
  return NAV_ITEMS.filter((item) =>
    isNavItemVisible(
      item,
      (...required) => userHasRole(me, required),
      (...required) => userHasExactRole(me, required),
    ),
  ).map((item) => item.label)
}

describe('NAV_ITEMS visibility', () => {
  it('hides personal-scope items from an ADMIN who does not literally hold the role', () => {
    const labels = visibleLabels(['ADMIN'])
    expect(labels).not.toContain('Solicitudes')
    expect(labels).not.toContain('Mi equipo')
    expect(labels).not.toContain('Arbitraje')
    // Management items keep the ADMIN implication.
    expect(labels).toContain('Jugadores')
    expect(labels).toContain('Usuarios')
    expect(labels).toContain('Auditoría')
  })

  it('shows personal-scope items to users who hold the role, including an ADMIN who is also a captain', () => {
    expect(visibleLabels(['PLAYER'])).toEqual(['Inicio', 'Torneos', 'Equipos', 'Mi perfil', 'Solicitudes', 'Mi equipo'])
    expect(visibleLabels(['PLAYER', 'CAPTAIN'])).toEqual([
      'Inicio',
      'Torneos',
      'Equipos',
      'Mi perfil',
      'Solicitudes',
      'Mi equipo',
      'Jugadores',
    ])
    expect(visibleLabels(['GUEST'])).not.toContain('Mi equipo')
    expect(visibleLabels(['ADMIN', 'CAPTAIN'])).toContain('Mi equipo')
    expect(visibleLabels(['REFEREE'])).toContain('Arbitraje')
  })

  it('Navbar renders only the visible entries', () => {
    const me = user(['ADMIN'])
    render(
      <MemoryRouter>
        <Navbar
          user={me}
          hasRole={(...roles) => userHasRole(me, roles)}
          hasExactRole={(...roles) => userHasExactRole(me, roles)}
          items={NAV_ITEMS}
          onLogout={() => undefined}
        />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('link', { name: 'Mi equipo' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Auditoría' })).toBeInTheDocument()
  })
})

describe('Navbar identity', () => {
  it('orders the roles by importance and keeps at most three for the header', () => {
    expect(headerRoles(['PLAYER', 'CAPTAIN', 'ADMIN'])).toEqual({
      visible: ['ADMIN', 'CAPTAIN', 'PLAYER'],
      overflow: false,
      full: 'Administrador · Capitán · Jugador',
    })
    expect(headerRoles(['PLAYER', 'GUEST', 'REFEREE', 'ORGANIZER'])).toEqual({
      visible: ['ORGANIZER', 'REFEREE', 'PLAYER'],
      overflow: true,
      full: 'Organizador · Árbitro · Jugador · Invitado',
    })
    expect(headerRoles([])).toEqual({ visible: [], overflow: false, full: '' })
  })

  it('shows only the first name, capitalised, with the full name in the tooltip and for screen readers', () => {
    const longName = 'carlos felipe rodriguez tellez'
    const me = { ...user(['PLAYER']), fullName: longName }
    render(
      <MemoryRouter>
        <Navbar user={me} hasRole={() => true} items={[]} onLogout={() => undefined} />
      </MemoryRouter>,
    )
    const name = screen.getByTestId('navbar-user-name')
    expect(name.querySelector('[aria-hidden="true"]')).toHaveTextContent(/^Carlos$/)
    expect(name.querySelector('.sr-only')).toHaveTextContent(longName)
    expect(name.className).toContain('truncate')
    expect(screen.getByTestId('navbar-identity')).toHaveAttribute('title', `${longName}\nJugador`)
    expect(screen.getByRole('img', { name: longName })).toBeInTheDocument()
  })

  it('shows up to three role badges and an "…" badge listing every role', () => {
    const me = user(['PLAYER', 'CAPTAIN', 'ORGANIZER', 'REFEREE', 'ADMIN'])
    render(
      <MemoryRouter>
        <Navbar user={me} hasRole={() => true} items={[]} onLogout={() => undefined} />
      </MemoryRouter>,
    )
    const roles = screen.getByTestId('navbar-roles')
    const all = 'Administrador · Organizador · Árbitro · Capitán · Jugador'
    expect(within(roles).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Administrador',
      'Organizador',
      'Árbitro',
      '…',
    ])
    expect(roles).toHaveAttribute('aria-label', `Roles: ${all}`)
    expect(within(roles).getByText('…').closest('li')).toHaveAttribute('title', all)
  })

  it('shows every badge without "…" when the user has three roles or fewer', () => {
    const me = user(['PLAYER', 'CAPTAIN'])
    render(
      <MemoryRouter>
        <Navbar user={me} hasRole={() => true} items={[]} onLogout={() => undefined} />
      </MemoryRouter>,
    )
    const roles = screen.getByTestId('navbar-roles')
    expect(within(roles).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Capitán', 'Jugador'])
    expect(within(roles).queryByText('…')).not.toBeInTheDocument()
  })
})

describe('Navbar desktop fit', () => {
  /** Fakes the links slot: `needed` px of links in `available` px of room (jsdom has no layout). */
  function fakeSlot(needed: number, available: number) {
    const isSlot = (element: HTMLElement) => element.dataset.testid === 'navbar-links-slot'
    const hasLinks = (element: HTMLElement) => !!element.querySelector('nav a')
    const scroll = vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
      return isSlot(this) && hasLinks(this) ? needed : 0
    })
    const client = vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
      return isSlot(this) ? available : 0
    })
    return () => {
      scroll.mockRestore()
      client.mockRestore()
    }
  }

  function renderNavbar() {
    const me = user(['PLAYER', 'CAPTAIN', 'ORGANIZER', 'REFEREE', 'ADMIN'])
    render(
      <MemoryRouter>
        <Navbar user={me} hasRole={() => true} items={NAV_ITEMS} onLogout={() => undefined} />
      </MemoryRouter>,
    )
  }

  it('keeps the links and the role badges when they fit', () => {
    const restore = fakeSlot(500, 600)
    renderNavbar()
    expect(screen.getByTestId('navbar-roles')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Principal' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Abrir menú' }).className).toContain('lg:hidden')
    restore()
  })

  it('drops the badges first and falls back to the menu button when the links still do not fit', () => {
    const restore = fakeSlot(900, 600)
    renderNavbar()
    // The first name always stays; badges and links give way, the menu button shows on desktop too.
    expect(screen.getByTestId('navbar-user-name')).toBeInTheDocument()
    expect(screen.queryByTestId('navbar-roles')).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Principal' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Abrir menú' }).className).not.toContain('lg:hidden')
    restore()
  })
})
