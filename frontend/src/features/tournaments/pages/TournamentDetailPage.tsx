import { useParams, useSearchParams } from 'react-router'
import { StatusBadge } from '@/components/atoms/Badge'
import { EmptyState } from '@/components/molecules/EmptyState'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { Tabs, type TabItem } from '@/components/molecules/Tabs'
import { BracketView } from '@/components/organisms/BracketView'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { formatDate } from '@/lib/format'
import { CaptainRegistration } from '../components/CaptainRegistration'
import { InfoTab } from '../components/InfoTab'
import { MatchesTab } from '../components/MatchesTab'
import { OrganizerPanel } from '../components/OrganizerPanel'
import { StandingsTab } from '../components/StandingsTab'
import { StatsTab } from '../components/StatsTab'
import { useBracket, useTournament } from '../hooks/useTournaments'

type TabId = 'info' | 'standings' | 'bracket' | 'matches' | 'stats' | 'register' | 'manage'
const TAB_IDS: TabId[] = ['info', 'standings', 'bracket', 'matches', 'stats', 'register', 'manage']

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
  const { user, hasRole, hasExactRole } = useAuth()
  const [searchParams, setSearchParams] = useSearchParams()
  const query = useTournament(tournamentId)
  const tournament = query.data

  const isOrganizer = hasRole('ORGANIZER')
  // Registering is personal: the captain's own team. ADMIN does not imply it (it has no team).
  const isCaptain = hasExactRole('CAPTAIN')

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
          {active === 'manage' && isOrganizer && (
            <OrganizerPanel
              tournament={tournament}
              canHardDelete={hasRole('ADMIN')}
              onUpdated={(update) =>
                query.setData((previous) => (typeof update === 'function' ? (previous ? update(previous) : previous) : update))
              }
            />
          )}
        </>
      )}
    </QueryState>
  )
}
