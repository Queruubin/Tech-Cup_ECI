import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/store/auth.store'
import type { Role, TeamResponse, UserResponse } from '@/types/api'
import { TeamDetailPage } from './pages/TeamDetailPage'

/** "Solicitar unirme" is personal: only a user who literally holds PLAYER sees it (ADMIN does not). */

const FIND = { timeout: 4000 }

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const team: TeamResponse = {
  id: 5,
  name: 'Leones FC',
  colors: 'Rojo',
  status: 'ACTIVE',
  captain: { id: 9, fullName: 'Ana Pérez' },
  members: [{ userId: 9, fullName: 'Ana Pérez', position: 'FORWARD', jerseyNumber: 9, academicProgram: 'SYSTEMS_ENGINEERING', photoFileId: null }],
  memberCount: 1,
  locked: false,
}

function signIn(roles: Role[]) {
  const user = {
    id: 1,
    fullName: 'Visitante',
    email: 'visitante@mail.com',
    schoolRelation: 'STUDENT',
    academicProgram: 'SYSTEMS_ENGINEERING',
    semester: 3,
    status: 'ACTIVE',
    birthDate: '2004-01-01',
    documentType: 'CC',
    documentNumber: '123',
    roles,
    hasProfile: true,
    teamId: null,
  } as UserResponse
  useAuthStore.setState({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z', user })
}

function renderTeam() {
  return render(
    <MemoryRouter initialEntries={['/teams/5']}>
      <Routes>
        <Route path="/teams/:id" element={<TeamDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input)
    if (url.endsWith('/eligibility')) return jsonResponse({ eligible: true, problems: [] })
    if (url.endsWith('/teams/5')) return jsonResponse(team)
    return jsonResponse({})
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ token: null, expiresAt: null, user: null })
})

describe('TeamDetailPage join request', () => {
  it('is offered to a free player', async () => {
    signIn(['PLAYER'])
    renderTeam()
    expect(await screen.findByRole('button', { name: 'Solicitar unirme' }, FIND)).toBeInTheDocument()
  })

  it('is not offered to an ADMIN without the PLAYER role', async () => {
    signIn(['ADMIN'])
    renderTeam()
    expect(await screen.findByRole('button', { name: 'Eliminar equipo' }, FIND)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Solicitar unirme' })).not.toBeInTheDocument()
  })
})
