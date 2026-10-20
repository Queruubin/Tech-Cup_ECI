import { useState } from 'react'
import { QueryState } from '@/components/molecules/QueryState'
import { StandingsTable } from '@/components/organisms/StandingsTable'
import { cn } from '@/lib/cn'
import type { GroupStandings, StandingRow } from '@/types/api'
import { useGroupStandings, useStandings } from '../hooks/useTournaments'
import { qualifierCount } from '../qualifiers'

type View = 'general' | 'groups'

const LEGEND =
  'PJ: jugados · PG: ganados · PE: empatados · PP: perdidos · GF/GC: goles a favor/en contra · DG: diferencia · Pts: puntos.'

export interface StandingsViewProps {
  general: StandingRow[]
  groups: GroupStandings[]
  highlightTeamId: number | null
}

/**
 * The general table and, when the tournament has groups, a "General | Por grupos" switch that shows
 * one table per group. Public information.
 */
export function StandingsView({ general, groups, highlightTeamId }: StandingsViewProps) {
  const [view, setView] = useState<View>('general')
  const hasGroups = groups.length > 0
  const shown: View = hasGroups ? view : 'general'

  return (
    <div className="flex flex-col gap-4">
      {hasGroups && (
        <div role="group" aria-label="Vista de posiciones" className="inline-flex self-start rounded-xl bg-stone-100 p-1">
          {(['general', 'groups'] as const).map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={shown === option}
              onClick={() => setView(option)}
              className={cn(
                'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
                shown === option ? 'bg-white text-ink shadow-sm' : 'text-stone-600 hover:text-ink',
              )}
            >
              {option === 'general' ? 'General' : 'Por grupos'}
            </button>
          ))}
        </div>
      )}
      {shown === 'general' ? (
        // With groups the qualifiers come from each group, so the general table has no cut line.
        <StandingsTable
          rows={general}
          highlightTeamId={highlightTeamId}
          qualifiedCount={hasGroups ? 0 : qualifierCount(general.length)}
        />
      ) : (
        <div className="flex flex-col gap-6">
          {groups.map((table) => (
            <section key={table.groupId} aria-label={table.name} className="flex flex-col gap-2">
              <h3 className="text-sm font-semibold text-ink">{table.name}</h3>
              <StandingsTable rows={table.rows} highlightTeamId={highlightTeamId} emptyMessage="El grupo no tiene equipos." />
            </section>
          ))}
        </div>
      )}
      <p className="text-xs text-stone-500">{LEGEND}</p>
    </div>
  )
}

/** Container: loads both tables; a failure of the group tables simply hides the switch. */
export function StandingsTab({ tournamentId, highlightTeamId }: { tournamentId: number; highlightTeamId: number | null }) {
  const general = useStandings(tournamentId)
  const groups = useGroupStandings(tournamentId)
  return (
    <QueryState loading={general.loading || groups.loading} error={general.error} onRetry={general.refetch}>
      <StandingsView general={general.data ?? []} groups={groups.data ?? []} highlightTeamId={highlightTeamId} />
    </QueryState>
  )
}
