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
}

export function MyTeamCard({ team, registration, isCaptain, isPlayer, tournamentOpen }: MyTeamCardProps) {
  if (!team) {
    return (
      <Card title="Mi equipo">
        <p className="text-sm text-stone-600">
          {isCaptain
            ? 'Aún no ha creado su equipo.'
            : isPlayer
              ? 'Todavía no pertenece a un equipo. Explore los equipos disponibles y envíe una solicitud.'
              : 'No pertenece a ningún equipo.'}
        </p>
        <div className="mt-3 flex flex-wrap gap-2">
          {isCaptain && (
            <Link to="/my-team">
              <Button size="sm">Crear equipo</Button>
            </Link>
          )}
          {isPlayer && (
            <Link to="/teams">
              <Button size="sm" variant="outline">
                Ver equipos
              </Button>
            </Link>
          )}
        </div>
      </Card>
    )
  }

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
        {isCaptain && !registration && tournamentOpen && (
          <Link to="/tournaments">
            <Button size="sm">Inscribir equipo</Button>
          </Link>
        )}
      </div>
    </Card>
  )
}
