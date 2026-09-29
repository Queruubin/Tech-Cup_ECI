import { Link } from 'react-router'
import { Badge, StatusBadge } from '@/components/atoms/Badge'
import { cn } from '@/lib/cn'
import { formatDate, formatMoney } from '@/lib/format'
import type { TournamentResponse } from '@/types/api'

export interface TournamentCardProps {
  tournament: TournamentResponse
  current?: boolean
}

export function TournamentCard({ tournament, current }: TournamentCardProps) {
  return (
    <Link
      to={`/tournaments/${tournament.id}`}
      className={cn(
        'flex flex-col gap-3 rounded-2xl border bg-white p-4 shadow-sm transition hover:shadow',
        current ? 'border-brand-400 ring-2 ring-brand-100' : 'border-stone-200 hover:border-brand-300',
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <h3 className="min-w-0 truncate text-base font-semibold text-ink">{tournament.name}</h3>
        <div className="flex shrink-0 flex-col items-end gap-1">
          <StatusBadge kind="tournament" value={tournament.status} />
          {current && <Badge tone="accent">Vigente</Badge>}
        </div>
      </div>
      <dl className="grid grid-cols-2 gap-x-3 gap-y-2 text-sm">
        <div>
          <dt className="text-xs text-stone-500">Inicio</dt>
          <dd className="text-stone-800">{formatDate(tournament.startDate)}</dd>
        </div>
        <div>
          <dt className="text-xs text-stone-500">Fin</dt>
          <dd className="text-stone-800">{formatDate(tournament.endDate)}</dd>
        </div>
        <div>
          <dt className="text-xs text-stone-500">Cierre inscripciones</dt>
          <dd className="text-stone-800">{formatDate(tournament.registrationDeadline)}</dd>
        </div>
        <div>
          <dt className="text-xs text-stone-500">Equipos</dt>
          <dd className="text-stone-800">
            {tournament.approvedTeams} / {tournament.maxTeams} · {formatMoney(tournament.fee)}
          </dd>
        </div>
      </dl>
    </Link>
  )
}
