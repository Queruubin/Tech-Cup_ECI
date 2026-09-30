import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { Navbar, isNavItemVisible } from '@/components/organisms/Navbar'
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
    expect(labels).not.toContain('Mis solicitudes')
    expect(labels).not.toContain('Mi equipo')
    expect(labels).not.toContain('Arbitraje')
    // Management items keep the ADMIN implication.
    expect(labels).toContain('Jugadores')
    expect(labels).toContain('Usuarios')
    expect(labels).toContain('Auditoría')
  })

  it('shows personal-scope items to users who hold the role, including an ADMIN who is also a captain', () => {
    expect(visibleLabels(['PLAYER'])).toEqual(['Inicio', 'Torneos', 'Equipos', 'Mi perfil', 'Mis solicitudes'])
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
