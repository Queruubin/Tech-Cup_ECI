import { Link } from 'react-router'
import { StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { Card } from '@/components/molecules/Card'
import { StatTile } from '@/components/molecules/StatTile'
import { formatDate, formatMoney } from '@/lib/format'
import type { TournamentResponse } from '@/types/api'

export interface TournamentSummaryProps {
  tournament: TournamentResponse
}

export function TournamentSummary({ tournament }: TournamentSummaryProps) {
  return (
    <Card
      title={tournament.name}
      description="Torneo vigente"
      actions={
        <>
          <StatusBadge kind="tournament" value={tournament.status} size="md" />
          <Link to={`/tournaments/${tournament.id}`}>
            <Button variant="outline" size="sm">
              Ver detalle
            </Button>
          </Link>
        </>
      }
    >
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatTile label="Inicio" value={<span className="text-base">{formatDate(tournament.startDate)}</span>} />
        <StatTile label="Fin" value={<span className="text-base">{formatDate(tournament.endDate)}</span>} />
        <StatTile
          label="Cierre inscripciones"
          value={<span className="text-base">{formatDate(tournament.registrationDeadline)}</span>}
        />
        <StatTile
          label="Equipos"
          value={`${tournament.approvedTeams}/${tournament.maxTeams}`}
          hint={`Inscripción: ${formatMoney(tournament.fee)}`}
        />
      </div>
      {tournament.venues.length > 0 && (
        <p className="mt-3 text-sm text-stone-600">
          <span className="font-medium text-stone-800">Canchas:</span>{' '}
          {tournament.venues.map((venue) => venue.name).join(', ')}
        </p>
      )}
    </Card>
  )
}
