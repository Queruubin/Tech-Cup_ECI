import type { ReactNode } from 'react'
import { StatusBadge } from '@/components/atoms/Badge'
import { cn } from '@/lib/cn'
import { formatDateTime } from '@/lib/format'
import { CANCEL_REASON_LABELS, MATCH_PHASE_LABELS } from '@/lib/labels'
import type { MatchResponse } from '@/types/api'

export interface MatchCardProps {
  match: MatchResponse
  /** Highlights this team's name (e.g. the viewer's team). */
  highlightTeamId?: number | null
  /** Optional actions rendered in the footer (buttons, links). */
  actions?: ReactNode
  className?: string
}

function TeamName({ name, highlighted, align }: { name: string; highlighted: boolean; align: 'left' | 'right' }) {
  return (
    <span
      className={cn(
        'min-w-0 flex-1 truncate text-sm font-medium',
        align === 'right' ? 'text-right' : 'text-left',
        highlighted ? 'text-brand-700' : 'text-ink',
      )}
    >
      {name}
    </span>
  )
}

function Score({ match }: { match: MatchResponse }) {
  if (match.status === 'PLAYED' && match.homeScore !== null && match.awayScore !== null) {
    const hasPenalties = match.homePenalties !== null && match.awayPenalties !== null
    return (
      <div className="flex flex-col items-center leading-none">
        <span className="rounded-lg bg-ink px-3 py-1.5 text-base font-semibold tabular-nums text-white">
          {match.homeScore} – {match.awayScore}
        </span>
        {hasPenalties && (
          <span className="mt-1 text-[11px] text-stone-500">
            Pen. {match.homePenalties} – {match.awayPenalties}
          </span>
        )}
      </div>
    )
  }
  return <span className="rounded-lg bg-stone-100 px-3 py-1.5 text-sm font-semibold text-stone-500">vs</span>
}

export function MatchCard({ match, highlightTeamId, actions, className }: MatchCardProps) {
  const roundLabel = match.phase === 'GROUP' ? `Jornada ${match.roundNumber}` : MATCH_PHASE_LABELS[match.phase]
  return (
    <article className={cn('rounded-2xl border border-stone-200 bg-white p-4 shadow-sm', className)}>
      <div className="mb-3 flex items-center justify-between gap-2 text-xs text-stone-500">
        <span className="font-medium uppercase tracking-wide">{roundLabel}</span>
        <StatusBadge kind="match" value={match.status} />
      </div>

      <div className="flex items-center gap-3">
        <TeamName name={match.homeTeam.name} highlighted={match.homeTeam.id === highlightTeamId} align="right" />
        <Score match={match} />
        <TeamName name={match.awayTeam.name} highlighted={match.awayTeam.id === highlightTeamId} align="left" />
      </div>

      <dl className="mt-3 grid grid-cols-1 gap-1 text-xs text-stone-600 sm:grid-cols-3">
        <div className="flex gap-1">
          <dt className="text-stone-400">Fecha:</dt>
          <dd>{formatDateTime(match.scheduledAt)}</dd>
        </div>
        <div className="flex gap-1">
          <dt className="text-stone-400">Cancha:</dt>
          <dd>{match.venue?.name ?? 'Por definir'}</dd>
        </div>
        <div className="flex gap-1">
          <dt className="text-stone-400">Árbitro:</dt>
          <dd>{match.referee?.fullName ?? 'Por definir'}</dd>
        </div>
      </dl>

      {match.status === 'CANCELLED' && match.cancelReason && (
        <p className="mt-2 text-xs text-brand-600">Motivo: {CANCEL_REASON_LABELS[match.cancelReason]}</p>
      )}

      {actions && <div className="mt-3 flex flex-wrap gap-2 border-t border-stone-100 pt-3">{actions}</div>}
    </article>
  )
}
