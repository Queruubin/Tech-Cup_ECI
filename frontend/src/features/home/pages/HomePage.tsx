import { Link } from 'react-router'
import { Button } from '@/components/atoms/Button'
import { Card } from '@/components/molecules/Card'
import { EmptyState } from '@/components/molecules/EmptyState'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { MatchCard } from '@/components/organisms/MatchCard'
import { StandingsTable } from '@/components/organisms/StandingsTable'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { MyTeamCard } from '../components/MyTeamCard'
import { TournamentSummary } from '../components/TournamentSummary'
import { useHome } from '../hooks/useHome'

export function HomePage() {
  const { user, hasRole } = useAuth()
  const { data, loading, error, refetch } = useHome()

  const firstName = user?.fullName.split(' ')[0] ?? ''
  const isCaptain = hasRole('CAPTAIN')
  const isPlayer = hasRole('PLAYER')

  return (
    <>
      <PageHeader
        title={firstName ? `Hola, ${firstName}` : 'Inicio'}
        description="Resumen del torneo vigente y de su participación."
      />
      <QueryState loading={loading} error={error} onRetry={refetch}>
        {data && (
          <div className="flex flex-col gap-6">
            {data.tournament ? (
              <TournamentSummary tournament={data.tournament} />
            ) : (
              <EmptyState
                title="No hay un torneo vigente"
                description="Cuando el organizador active un torneo, aquí verá las fechas, la tabla de posiciones y los próximos partidos."
                action={
                  hasRole('ORGANIZER') ? (
                    <Link to="/tournaments">
                      <Button size="sm">Gestionar torneos</Button>
                    </Link>
                  ) : undefined
                }
              />
            )}

            <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
              <div className="flex flex-col gap-6 lg:col-span-1">
                <MyTeamCard
                  team={data.myTeam}
                  registration={data.myRegistration}
                  isCaptain={isCaptain}
                  isPlayer={isPlayer}
                  tournamentOpen={data.tournament?.status === 'ACTIVE'}
                />
              </div>

              <div className="flex flex-col gap-6 lg:col-span-2">
                <Card
                  title="Próximos partidos"
                  padded={false}
                  actions={
                    data.tournament && (
                      <Link to={`/tournaments/${data.tournament.id}`} className="text-sm font-medium text-brand-700 hover:underline">
                        Ver calendario
                      </Link>
                    )
                  }
                >
                  {data.upcomingMatches.length === 0 ? (
                    <p className="px-5 py-6 text-sm text-stone-500">No hay partidos programados por el momento.</p>
                  ) : (
                    <div className="grid grid-cols-1 gap-3 p-4 md:grid-cols-2">
                      {data.upcomingMatches.map((match) => (
                        <MatchCard
                          key={match.id}
                          match={match}
                          highlightTeamId={data.myTeam?.id ?? null}
                          actions={
                            <Link to={`/matches/${match.id}`} className="text-sm font-medium text-brand-700 hover:underline">
                              Ver partido
                            </Link>
                          }
                        />
                      ))}
                    </div>
                  )}
                </Card>

                <Card
                  title="Tabla de posiciones"
                  description="Primeros lugares de la fase de grupos"
                  padded={false}
                  actions={
                    data.tournament && (
                      <Link
                        to={`/tournaments/${data.tournament.id}?tab=standings`}
                        className="text-sm font-medium text-brand-700 hover:underline"
                      >
                        Ver tabla completa
                      </Link>
                    )
                  }
                >
                  <div className="p-4">
                    <StandingsTable rows={data.standingsTop} highlightTeamId={data.myTeam?.id ?? null} compact />
                  </div>
                </Card>
              </div>
            </div>
          </div>
        )}
      </QueryState>
    </>
  )
}
