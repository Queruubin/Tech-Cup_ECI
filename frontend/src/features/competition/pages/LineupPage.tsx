import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '@/components/atoms/Button'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { EmptyState } from '@/components/molecules/EmptyState'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { Tabs, type TabItem } from '@/components/molecules/Tabs'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { useMyTeam } from '@/features/teams/hooks/useTeams'
import { formatDateTime, isFuture } from '@/lib/format'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { UpsertLineupRequest } from '@/types/api'
import { competitionApi } from '../api'
import { DEFAULT_FORMATION, LineupEditor } from '../components/LineupEditor'
import { LineupView } from '../components/LineupView'
import { useLineup, useMatch } from '../hooks/useCompetition'

export function LineupPage() {
  const params = useParams<{ id: string }>()
  const matchId = params.id && /^\d+$/.test(params.id) ? Number(params.id) : null
  const { user, hasRole } = useAuth()

  const matchQuery = useMatch(matchId)
  const match = matchQuery.data
  const myTeamQuery = useMyTeam()
  const myTeam = myTeamQuery.data

  const viewerTeamId = myTeam?.id ?? user?.teamId ?? null
  const isParticipant = !!match && (viewerTeamId === match.homeTeam.id || viewerTeamId === match.awayTeam.id)
  const isCaptain = hasRole('CAPTAIN') && !!myTeam && !!user && myTeam.captain.id === user.id
  // Only the referee assigned to this match may browse both lineups; other referees are ordinary users.
  const isAssignedReferee = hasRole('REFEREE') && !!match && !!user && match.referee?.id === user.id
  const canSwitchTeams = hasRole('ORGANIZER') || isAssignedReferee

  const [selectedTeamId, setSelectedTeamId] = useState<number | null>(null)
  const defaultTeamId = isParticipant ? viewerTeamId : (match?.homeTeam.id ?? null)
  const activeTeamId = selectedTeamId ?? defaultTeamId

  const lineupQuery = useLineup(matchId, activeTeamId)
  const lineup = lineupQuery.data

  const save = useMutation((payload: UpsertLineupRequest) => competitionApi.upsertLineup(matchId as number, payload))

  const beforeKickoff = !!match && match.status === 'SCHEDULED' && isFuture(match.scheduledAt)
  const canEdit = isCaptain && isParticipant && activeTeamId === viewerTeamId && beforeKickoff
  const canView = canSwitchTeams || isParticipant

  if (matchId === null) {
    return <EmptyState title="Partido no encontrado" description="El identificador del partido no es válido." />
  }

  const teams = match ? [match.homeTeam, match.awayTeam] : []
  const tabs: TabItem<string>[] = teams.map((team) => ({ id: String(team.id), label: team.name }))

  return (
    <QueryState loading={matchQuery.loading || myTeamQuery.loading} error={matchQuery.error} onRetry={matchQuery.refetch}>
      {match && (
        <>
          <PageHeader
            title="Alineación"
            description={`${match.homeTeam.name} vs ${match.awayTeam.name} · ${formatDateTime(match.scheduledAt)}`}
            actions={
              <Link to={`/matches/${match.id}`}>
                <Button size="sm" variant="outline">
                  Ver partido
                </Button>
              </Link>
            }
          />

          {!canView ? (
            <EmptyState
              title="Sin permisos"
              description="Solo los integrantes de los equipos que disputan el partido, el organizador y el árbitro asignado pueden ver la alineación."
              action={
                <Link to={`/matches/${match.id}`}>
                  <Button size="sm" variant="outline">
                    Volver al partido
                  </Button>
                </Link>
              }
            />
          ) : (
            <>
              {canSwitchTeams && activeTeamId !== null && (
                <Tabs tabs={tabs} active={String(activeTeamId)} onChange={(id) => setSelectedTeamId(Number(id))} className="mb-6" />
              )}

              {canEdit ? (
                <Card
                  title="Definir titulares"
                  description="Seleccione la formación y exactamente siete titulares. Puede modificarla hasta el inicio del partido."
                >
                  <QueryState loading={lineupQuery.loading} error={lineupQuery.error} onRetry={lineupQuery.refetch} inline>
                    <LineupEditor
                      key={lineup ? `lineup-${lineup.teamId}-${lineup.formation}` : 'lineup-new'}
                      members={myTeam?.members ?? []}
                      initialFormation={lineup?.formation ?? DEFAULT_FORMATION}
                      initialStarterIds={lineup?.starters.map((player) => player.userId) ?? []}
                      loading={save.loading}
                      error={save.error}
                      onSubmit={(payload) =>
                        save
                          .mutate(payload)
                          .then((updated) => {
                            lineupQuery.setData(updated)
                            toast.success('Alineación guardada.')
                          })
                          .catch(() => undefined)
                      }
                    />
                  </QueryState>
                </Card>
              ) : (
                <Card title="Alineación registrada">
                  {isParticipant && isCaptain && !beforeKickoff && (
                    <Alert kind="info" className="mb-4">
                      La alineación solo se puede modificar antes de la hora programada del partido.
                    </Alert>
                  )}
                  <QueryState loading={lineupQuery.loading} error={lineupQuery.error} onRetry={lineupQuery.refetch} inline>
                    {lineup ? (
                      <LineupView lineup={lineup} />
                    ) : (
                      <EmptyState
                        title="Sin alineación"
                        description="El capitán de este equipo aún no ha registrado la alineación para este partido."
                      />
                    )}
                  </QueryState>
                </Card>
              )}
            </>
          )}
        </>
      )}
    </QueryState>
  )
}
