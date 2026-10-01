import { Link } from 'react-router'
import { StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { Card } from '@/components/molecules/Card'
import { formatDate } from '@/lib/format'
import type { RegistrationResponse, TeamResponse } from '@/types/api'

export interface MyTeamCardProps {
  team: TeamResponse | null
  registration: RegistrationResponse | null
  isCaptain: boolean
  isPlayer: boolean
  tournamentOpen: boolean
  /** Current tournament, used to deep-link the captain to its registration tab. */
  tournamentId?: number | null
}

/** A rejected or cancelled registration may be submitted again. */
export function canRegisterAgain(registration: RegistrationResponse | null): boolean {
  return registration === null || registration.status === 'REJECTED' || registration.status === 'CANCELLED'
}

export function registrationHref(tournamentId: number | null | undefined): string {
  return tournamentId ? `/tournaments/${tournamentId}?tab=register` : '/tournaments'
}

export function MyTeamCard({ team, registration, isCaptain, isPlayer, tournamentOpen, tournamentId = null }: MyTeamCardProps) {
  if (!team) {
    // Any player (or captain) without a team may create one and becomes its captain.
    const canCreate = isCaptain || isPlayer
    return (
      <Card title="Mi equipo">
        <p className="text-sm text-stone-600">
          {canCreate
            ? 'Todavía no pertenece a un equipo. Cree el suyo y será su capitán, o solicite unirse a uno existente.'
            : 'No pertenece a ningún equipo.'}
        </p>
        {canCreate && (
          <div className="mt-3 flex flex-wrap gap-2">
            <Link to="/my-team">
              <Button size="sm">Crear equipo</Button>
            </Link>
            <Link to="/teams">
              <Button size="sm" variant="outline">
                Ver equipos
              </Button>
            </Link>
          </div>
        )}
      </Card>
    )
  }

  const showRegisterCta = isCaptain && tournamentOpen && canRegisterAgain(registration)

  return (
    <Card
      title={team.name}
      description={`Capitán: ${team.captain.fullName}`}
      actions={<StatusBadge kind="team" value={team.status} />}
    >
      <dl className="grid grid-cols-2 gap-3 text-sm">
        <div>
          <dt className="text-stone-500">Colores</dt>
          <dd className="font-medium text-ink">{team.colors}</dd>
        </div>
        <div>
          <dt className="text-stone-500">Integrantes</dt>
          <dd className="font-medium text-ink">{team.memberCount} / 12</dd>
        </div>
        <div className="col-span-2">
          <dt className="text-stone-500">Inscripción</dt>
          <dd className="mt-0.5 flex flex-wrap items-center gap-2">
            {registration ? (
              <>
                <StatusBadge kind="registration" value={registration.status} />
                <span className="text-xs text-stone-500">Enviada el {formatDate(registration.createdAt)}</span>
              </>
            ) : (
              <span className="text-stone-700">Sin inscripción en el torneo vigente.</span>
            )}
          </dd>
        </div>
      </dl>
      <div className="mt-4 flex flex-wrap gap-2">
        <Link to={isCaptain ? '/my-team' : `/teams/${team.id}`}>
          <Button size="sm" variant="outline">
            {isCaptain ? 'Gestionar equipo' : 'Ver equipo'}
          </Button>
        </Link>
        {showRegisterCta && (
          <Link to={registrationHref(tournamentId)}>
            <Button size="sm">{registration ? 'Inscribir equipo de nuevo' : 'Inscribir equipo'}</Button>
          </Link>
        )}
      </div>
    </Card>
  )
}
