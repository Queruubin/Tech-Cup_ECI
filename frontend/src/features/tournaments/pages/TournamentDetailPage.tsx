import { useParams, useSearchParams } from 'react-router'
import { StatusBadge } from '@/components/atoms/Badge'
import { EmptyState } from '@/components/molecules/EmptyState'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { Tabs, type TabItem } from '@/components/molecules/Tabs'
import { BracketView } from '@/components/organisms/BracketView'
import { StandingsTable } from '@/components/organisms/StandingsTable'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { formatDate } from '@/lib/format'
import { CaptainRegistration } from '../components/CaptainRegistration'
import { InfoTab } from '../components/InfoTab'
import { MatchesTab } from '../components/MatchesTab'
import { OrganizerPanel } from '../components/OrganizerPanel'
import { StatsTab } from '../components/StatsTab'
import { useBracket, useStandings, useTournament } from '../hooks/useTournaments'

type TabId = 'info' | 'standings' | 'bracket' | 'matches' | 'stats' | 'register' | 'manage'
const TAB_IDS: TabId[] = ['info', 'standings', 'bracket', 'matches', 'stats', 'register', 'manage']

function StandingsTab({ tournamentId, highlightTeamId }: { tournamentId: number; highlightTeamId: number | null }) {
  const { data, loading, error, refetch } = useStandings(tournamentId)
  return (
    <QueryState loading={loading} error={error} onRetry={refetch}>
      <StandingsTable rows={data ?? []} highlightTeamId={highlightTeamId} qualifiedCount={data && data.length >= 8 ? 8 : data && data.length >= 4 ? 4 : 0} />
      <p className="mt-2 text-xs text-stone-500">PJ: jugados · PG: ganados · PE: empatados · PP: perdidos · GF/GC: goles a favor/en contra · DG: diferencia · Pts: puntos.</p>
    </QueryState>
  )
}

function BracketTab({ tournamentId }: { tournamentId: number }) {
  const { data, loading, error, refetch } = useBracket(tournamentId)
  return (
    <QueryState loading={loading} error={error} onRetry={refetch}>
      <BracketView phases={data?.phases ?? []} matchHref={(match) => `/matches/${match.id}`} />
    </QueryState>
  )
}

export function TournamentDetailPage() {
  const params = useParams<{ id: string }>()
  const tournamentId = params.id && /^\d+$/.test(params.id) ? Number(params.id) : null
  const { user, hasRole } = useAuth()
  const [searchParams, setSearchParams] = useSearchParams()
  const query = useTournament(tournamentId)
  const tournament = query.data

  const isOrganizer = hasRole('ORGANIZER')
  const isCaptain = hasRole('CAPTAIN')

  if (tournamentId === null) {
    return <EmptyState title="Torneo no encontrado" description="El identificador del torneo no es válido." />
  }

  const tabs: TabItem<TabId>[] = [
    { id: 'info', label: 'Información' },
    { id: 'standings', label: 'Posiciones' },
    { id: 'bracket', label: 'Llaves' },
    { id: 'matches', label: 'Partidos' },
    { id: 'stats', label: 'Estadísticas' },
  ]
  if (isCaptain) tabs.push({ id: 'register', label: 'Inscripción' })
  if (isOrganizer) tabs.push({ id: 'manage', label: 'Gestión' })

  const requested = searchParams.get('tab')
  const active: TabId = TAB_IDS.includes(requested as TabId) && tabs.some((tab) => tab.id === requested) ? (requested as TabId) : 'info'
  const setActive = (id: TabId) => {
    const next = new URLSearchParams(searchParams)
    if (id === 'info') next.delete('tab')
    else next.set('tab', id)
    setSearchParams(next, { replace: true })
  }

  return (
    <QueryState loading={query.loading} error={query.error} onRetry={query.refetch}>
      {tournament && (
        <>
          <PageHeader
            title={tournament.name}
            description={
              <span className="flex flex-wrap items-center gap-2">
                <StatusBadge kind="tournament" value={tournament.status} />
                <span>
                  {formatDate(tournament.startDate)} – {formatDate(tournament.endDate)}
                </span>
                <span>· {tournament.approvedTeams} equipos aprobados</span>
              </span>
            }
          />
          <Tabs tabs={tabs} active={active} onChange={setActive} className="mb-6" />

          {active === 'info' && <InfoTab tournament={tournament} />}
          {active === 'standings' && <StandingsTab tournamentId={tournament.id} highlightTeamId={user?.teamId ?? null} />}
          {active === 'bracket' && <BracketTab tournamentId={tournament.id} />}
          {active === 'matches' && <MatchesTab tournamentId={tournament.id} highlightTeamId={user?.teamId ?? null} />}
          {active === 'stats' && <StatsTab tournamentId={tournament.id} />}
          {active === 'register' && isCaptain && <CaptainRegistration tournament={tournament} onRegistered={query.refetch} />}
          {active === 'manage' && isOrganizer && <OrganizerPanel tournament={tournament} onUpdated={(updated) => query.setData(updated)} />}
        </>
      )}
    </QueryState>
  )
}
