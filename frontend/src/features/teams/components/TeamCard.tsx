import { Link } from 'react-router'
import { Badge, StatusBadge } from '@/components/atoms/Badge'
import type { TeamResponse } from '@/types/api'

export interface TeamCardProps {
  team: TeamResponse
  isMine?: boolean
}

export function TeamCard({ team, isMine }: TeamCardProps) {
  return (
    <Link
      to={`/teams/${team.id}`}
      className="flex flex-col gap-3 rounded-2xl border border-stone-200 bg-white p-4 shadow-sm transition hover:border-brand-300 hover:shadow"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="truncate text-base font-semibold text-ink">{team.name}</h3>
          <p className="truncate text-xs text-stone-500">Capitán: {team.captain.fullName}</p>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1">
          <StatusBadge kind="team" value={team.status} />
          {isMine && <Badge tone="accent">Mi equipo</Badge>}
        </div>
      </div>
      <dl className="flex items-center justify-between text-sm">
        <div>
          <dt className="text-xs text-stone-500">Colores</dt>
          <dd className="font-medium text-stone-800">{team.colors}</dd>
        </div>
        <div className="text-right">
          <dt className="text-xs text-stone-500">Integrantes</dt>
          <dd className="font-medium text-stone-800">{team.memberCount} / 12</dd>
        </div>
      </dl>
      {team.locked && <p className="text-xs text-amber-700">Inscrito en un torneo en curso.</p>}
    </Link>
  )
}
