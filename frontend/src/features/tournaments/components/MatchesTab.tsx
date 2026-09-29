import { useState } from 'react'
import { Link } from 'react-router'
import { Select } from '@/components/atoms/Select'
import { EmptyState } from '@/components/molecules/EmptyState'
import { QueryState } from '@/components/molecules/QueryState'
import { MatchCard } from '@/components/organisms/MatchCard'
import { MATCH_PHASE_LABELS, toOptions } from '@/lib/labels'
import { MATCH_PHASES, type MatchPhase } from '@/types/api'
import { useTournamentMatches } from '../hooks/useTournaments'

const PHASE_OPTIONS = toOptions(MATCH_PHASES, MATCH_PHASE_LABELS)

export function MatchesTab({ tournamentId, highlightTeamId }: { tournamentId: number; highlightTeamId: number | null }) {
  const [phase, setPhase] = useState<MatchPhase | ''>('')
  const { data, loading, error, refetch } = useTournamentMatches(tournamentId, phase)

  const sorted = [...(data ?? [])].sort((a, b) => {
    if (a.phase !== b.phase) return MATCH_PHASES.indexOf(a.phase) - MATCH_PHASES.indexOf(b.phase)
    if (a.roundNumber !== b.roundNumber) return a.roundNumber - b.roundNumber
    return (a.scheduledAt ?? '').localeCompare(b.scheduledAt ?? '')
  })

  return (
    <div className="flex flex-col gap-4">
      <div className="flex justify-end">
        <label className="flex items-center gap-2 text-sm text-stone-600">
          Fase
          <Select
            options={PHASE_OPTIONS}
            placeholder="Todas"
            value={phase}
            onChange={(event) => setPhase(event.target.value as MatchPhase | '')}
            className="w-48"
          />
        </label>
      </div>
      <QueryState loading={loading} error={error} onRetry={refetch}>
        {sorted.length === 0 ? (
          <EmptyState title="Sin partidos" description="Los partidos aparecerán cuando el organizador genere el fixture." />
        ) : (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            {sorted.map((match) => (
              <MatchCard
                key={match.id}
                match={match}
                highlightTeamId={highlightTeamId}
                actions={
                  <Link to={`/matches/${match.id}`} className="text-sm font-medium text-brand-700 hover:underline">
                    Ver partido
                  </Link>
                }
              />
            ))}
          </div>
        )}
      </QueryState>
    </div>
  )
}
