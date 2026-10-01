import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { UserResponse } from '@/types/api'
import { UsersTable } from './components/UsersTable'

const player: UserResponse = {
  id: 7,
  fullName: 'Luis Gómez',
  email: 'luis@gmail.com',
  schoolRelation: 'FAMILY',
  academicProgram: 'OTHER',
  semester: null,
  status: 'ACTIVE',
  birthDate: null,
  documentType: null,
  documentNumber: null,
  roles: ['PLAYER', 'CAPTAIN'],
  hasProfile: true,
  teamId: 3,
}

function renderTable(isAdmin: boolean) {
  render(
    <UsersTable
      users={[player]}
      currentUserId={1}
      canManageRoles={isAdmin}
      canInactivate={isAdmin}
      canResetPassword={isAdmin}
      canEditUser={isAdmin}
      busyUserId={null}
      onManageRoles={() => undefined}
      onInactivate={() => undefined}
      onResetPassword={() => undefined}
      onEditUser={() => undefined}
    />,
  )
}

describe('UsersTable actions', () => {
  it('no longer offers granting or revoking the captain role', () => {
    renderTable(false)
    expect(screen.queryByRole('button', { name: /capitán/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Editar datos' })).not.toBeInTheDocument()
  })

  it('offers "Editar datos" to an admin', () => {
    renderTable(true)
    expect(screen.getByRole('button', { name: 'Editar datos' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /capitán/i })).not.toBeInTheDocument()
  })
})
