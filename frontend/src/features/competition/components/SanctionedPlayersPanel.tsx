import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { QueryState } from '@/components/molecules/QueryState'
import { useSanctionedPlayers } from '../hooks/useCompetition'

export interface SanctionedPlayersPanelProps {
  matchId: number
  /** Starts expanded (used on the referee list when a single match is open). */
  defaultOpen?: boolean
}

/**
 * Collapsible list of players suspended for this match (red card or an even number of
 * accumulated yellow cards in the previous played match). Fetches only once expanded.
 */
export function SanctionedPlayersPanel({ matchId, defaultOpen = false }: SanctionedPlayersPanelProps) {
  const [open, setOpen] = useState(defaultOpen)
  const query = useSanctionedPlayers(matchId, open)

  return (
    <div className="w-full">
      <Button size="sm" variant="outline" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
        {open ? 'Ocultar sancionados' : 'Ver sancionados'}
      </Button>
      {open && (
        <div className="mt-3">
          <QueryState loading={query.loading} error={query.error} onRetry={query.refetch} inline>
            {(query.data ?? []).length === 0 ? (
              <p className="text-sm text-stone-500">No hay jugadores sancionados para este partido.</p>
            ) : (
              <ul className="divide-y divide-stone-100 rounded-xl border border-amber-200 bg-amber-50/60">
                {(query.data ?? []).map((player) => (
                  <li key={`${player.teamId}-${player.userId}`} className="px-4 py-2.5">
                    <p className="text-sm font-medium text-ink">{player.fullName}</p>
                    <p className="text-xs text-stone-600">
                      {player.teamName} · {player.reason}
                    </p>
                  </li>
                ))}
              </ul>
            )}
          </QueryState>
        </div>
      )}
    </div>
  )
}
