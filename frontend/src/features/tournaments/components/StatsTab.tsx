import { useState } from 'react'
import { Link } from 'react-router'
import { Select } from '@/components/atoms/Select'
import { Card } from '@/components/molecules/Card'
import { QueryState } from '@/components/molecules/QueryState'
import { Table, type Column } from '@/components/molecules/Table'
import { MatchCard } from '@/components/organisms/MatchCard'
import type { MatchResponse, TopScorer } from '@/types/api'
import { useMatchHistory, useStandings, useTeamResults, useTopScorers } from '../hooks/useTournaments'

function MatchList({ matches, emptyMessage }: { matches: MatchResponse[]; emptyMessage: string }) {
  if (matches.length === 0) return <p className="text-sm text-stone-500">{emptyMessage}</p>
  return (
    <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
      {matches.map((match) => (
        <MatchCard
          key={match.id}
          match={match}
          actions={
            <Link to={`/matches/${match.id}`} className="text-sm font-medium text-brand-700 hover:underline">
              Ver partido
            </Link>
          }
        />
      ))}
    </div>
  )
}

export function StatsTab({ tournamentId }: { tournamentId: number }) {
  const scorers = useTopScorers(tournamentId)
  const history = useMatchHistory(tournamentId)
  const standings = useStandings(tournamentId)
  const [teamId, setTeamId] = useState<string>('')
  const results = useTeamResults(tournamentId, teamId ? Number(teamId) : null)

  const scorerColumns: Column<TopScorer>[] = [
    { key: 'pos', header: '#', align: 'center', className: 'w-10', cell: (_row) => '' },
    { key: 'player', header: 'Jugador', cell: (row) => <span className="font-medium text-ink">{row.playerName}</span> },
    {
      key: 'team',
      header: 'Equipo',
      cell: (row) => (
        <Link to={`/teams/${row.teamId}`} className="hover:text-brand-700">
          {row.teamName}
        </Link>
      ),
    },
    { key: 'goals', header: 'Goles', align: 'center', cell: (row) => <span className="font-semibold">{row.goals}</span> },
  ]
  const indexedColumns = scorerColumns.map((column) =>
    column.key === 'pos'
      ? { ...column, cell: (row: TopScorer) => (scorers.data?.indexOf(row) ?? 0) + 1 }
      : column,
  )

  const teamOptions = (standings.data ?? []).map((row) => ({ value: String(row.teamId), label: row.teamName }))

  return (
    <div className="flex flex-col gap-6">
      <Card title="Máximos goleadores" padded={false}>
        <div className="p-4">
          <QueryState loading={scorers.loading} error={scorers.error} onRetry={scorers.refetch} inline>
            <Table
              columns={indexedColumns}
              rows={scorers.data ?? []}
              rowKey={(row) => row.playerId}
              dense
              empty="Aún no se han registrado goles."
            />
          </QueryState>
        </div>
      </Card>

      <Card
        title="Resultados por equipo"
        actions={
          <Select
            options={teamOptions}
            placeholder="Seleccione un equipo"
            value={teamId}
            onChange={(event) => setTeamId(event.target.value)}
            className="w-56"
            aria-label="Equipo"
          />
        }
      >
        {teamId ? (
          <QueryState loading={results.loading} error={results.error} onRetry={results.refetch} inline>
            <MatchList matches={results.data ?? []} emptyMessage="Este equipo no tiene partidos registrados." />
          </QueryState>
        ) : (
          <p className="text-sm text-stone-500">Seleccione un equipo para ver sus resultados.</p>
        )}
      </Card>

      <Card title="Historial de partidos" description="Partidos jugados, del más reciente al más antiguo.">
        <QueryState loading={history.loading} error={history.error} onRetry={history.refetch} inline>
          <MatchList matches={history.data ?? []} emptyMessage="Todavía no se han jugado partidos." />
        </QueryState>
      </Card>
    </div>
  )
}
