import { Link } from 'react-router'
import { formatDate } from '@/lib/format'
import type { TournamentHistorySummary } from '@/types/api'
import { historyPath } from '../history'

/** A finished tournament in "Torneos anteriores": dates and champion, linking to its history. */
export function PastTournamentCard({ tournament }: { tournament: TournamentHistorySummary }) {
  return (
    <Link
      to={historyPath(tournament.id)}
      className="flex flex-col gap-2 rounded-2xl border border-stone-200 bg-white p-4 shadow-sm transition hover:border-brand-300 hover:shadow"
    >
      <h3 className="min-w-0 truncate text-base font-semibold text-ink">{tournament.name}</h3>
      <p className="text-xs text-stone-500">
        {formatDate(tournament.startDate)} – {formatDate(tournament.endDate)}
      </p>
      <p className="text-sm text-stone-700">
        <span className="text-xs font-semibold uppercase tracking-wide text-brand-700">Campeón</span>{' '}
        {tournament.champion ? tournament.champion.teamName : <span className="text-stone-500">Sin campeón</span>}
      </p>
      <span className="text-xs font-medium text-brand-700">Ver historial →</span>
    </Link>
  )
}
