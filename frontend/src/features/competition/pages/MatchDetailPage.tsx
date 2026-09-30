import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '@/components/atoms/Button'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { EmptyState } from '@/components/molecules/EmptyState'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { useTeam } from '@/features/teams/hooks/useTeams'
import { useTournament } from '@/features/tournaments/hooks/useTournaments'
import { isFuture } from '@/lib/format'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { MatchResultRequest, UpdateMatchRequest } from '@/types/api'
import { competitionApi } from '../api'
import { CancelMatchDialog, type CancelMatchInput } from '../components/CancelMatchDialog'
import { MatchEditForm } from '../components/MatchEditForm'
import { MatchEventsList } from '../components/MatchEventsList'
import { MatchScoreboard } from '../components/MatchScoreboard'
import { ResultForm } from '../components/ResultForm'
import { SanctionedPlayersPanel } from '../components/SanctionedPlayersPanel'
import { useMatch, useReferees } from '../hooks/useCompetition'
import { resultValuesFromMatch } from '../validation'

export function MatchDetailPage() {
  const params = useParams<{ id: string }>()
  const matchId = params.id && /^\d+$/.test(params.id) ? Number(params.id) : null
  const { user, hasRole } = useAuth()
  const isOrganizer = hasRole('ORGANIZER')
  const isReferee = hasRole('REFEREE')

  const query = useMatch(matchId)
  const match = query.data
  // Rescheduling only makes sense before kick-off.
  const editable = !!match && match.status === 'SCHEDULED' && isFuture(match.scheduledAt)
  // Cancelling (e.g. NO_SHOW) happens after kick-off too: the server only requires a SCHEDULED match
  // of a tournament in progress.
  const cancellable = !!match && match.status === 'SCHEDULED'
  // The server decides when a result can be recorded or corrected.
  const canRecordResult = !!match && match.resultEditable
  const isCorrection = !!match && match.status === 'PLAYED'

  const tournament = useTournament(isOrganizer && match ? match.tournamentId : null)
  const referees = useReferees(isOrganizer && !!match)
  const homeRoster = useTeam(isOrganizer && canRecordResult && match ? match.homeTeam.id : null)
  const awayRoster = useTeam(isOrganizer && canRecordResult && match ? match.awayTeam.id : null)

  const [editing, setEditing] = useState(false)
  const [cancelOpen, setCancelOpen] = useState(false)

  const update = useMutation((payload: UpdateMatchRequest) => competitionApi.updateMatch(matchId as number, payload))
  const cancel = useMutation((input: CancelMatchInput) =>
    competitionApi.cancelMatch(matchId as number, input.reason, input.winnerTeamId),
  )
  const result = useMutation((payload: MatchResultRequest) => competitionApi.recordResult(matchId as number, payload))

  if (matchId === null) {
    return <EmptyState title="Partido no encontrado" description="El identificador del partido no es válido." />
  }

  return (
    <QueryState loading={query.loading} error={query.error} onRetry={query.refetch}>
      {match && (
        <>
          <PageHeader
            title={`${match.homeTeam.name} vs ${match.awayTeam.name}`}
            description="Detalle del partido, eventos y alineaciones."
            actions={
              <>
                <Link to={`/tournaments/${match.tournamentId}?tab=matches`}>
                  <Button size="sm" variant="ghost">
                    Ver torneo
                  </Button>
                </Link>
                <Link to={`/matches/${match.id}/lineup`}>
                  <Button size="sm" variant="outline">
                    Alineaciones
                  </Button>
                </Link>
              </>
            }
          />

          <div className="flex flex-col gap-6">
            <MatchScoreboard match={match} highlightTeamId={user?.teamId ?? null} />

            <Card title="Eventos" description="Goles y tarjetas registrados por el organizador.">
              <MatchEventsList events={match.events} homeTeam={match.homeTeam} awayTeam={match.awayTeam} />
            </Card>

            {(isOrganizer || isReferee) && (
              <Card
                title="Jugadores sancionados"
                description="Jugadores que no pueden alinear en este partido por tarjeta roja o acumulación de amarillas."
              >
                <SanctionedPlayersPanel matchId={match.id} />
              </Card>
            )}

            {isOrganizer && (
              <Card
                title="Programación"
                description={
                  editable
                    ? 'Puede reprogramar el partido y reasignar cancha y árbitro mientras no haya comenzado.'
                    : cancellable
                      ? 'La fecha ya pasó: solo es posible cancelar el partido (por ejemplo, por no presentación).'
                      : 'La programación solo se puede editar mientras la fecha del partido sea futura y el partido esté programado.'
                }
                actions={
                  cancellable && (
                    <div className="flex flex-wrap gap-2">
                      {editable && (
                        <Button size="sm" variant="outline" onClick={() => setEditing((value) => !value)}>
                          {editing ? 'Cerrar edición' : 'Editar'}
                        </Button>
                      )}
                      <Button size="sm" variant="danger" onClick={() => setCancelOpen(true)}>
                        Cancelar partido
                      </Button>
                    </div>
                  )
                }
              >
                {editable && editing ? (
                  <QueryState
                    loading={tournament.loading || referees.loading}
                    error={tournament.error ?? referees.error}
                    onRetry={() => {
                      tournament.refetch()
                      referees.refetch()
                    }}
                    inline
                  >
                    <MatchEditForm
                      match={match}
                      venues={tournament.data?.venues ?? []}
                      referees={referees.data ?? []}
                      loading={update.loading}
                      error={update.error}
                      fieldErrors={update.fieldErrors}
                      onCancel={() => setEditing(false)}
                      onSubmit={(payload) =>
                        update
                          .mutate(payload)
                          .then((updated) => {
                            query.setData(updated)
                            toast.success('Partido actualizado.')
                            setEditing(false)
                          })
                          .catch(() => undefined)
                      }
                    />
                  </QueryState>
                ) : (
                  <p className="text-sm text-stone-500">
                    {editable
                      ? 'Seleccione “Editar” para modificar la fecha, la cancha o el árbitro.'
                      : cancellable
                        ? 'El partido ya no se puede reprogramar. Si un equipo no se presentó, cancélelo con el motivo correspondiente.'
                        : 'No hay acciones de programación disponibles para este partido.'}
                  </p>
                )}
              </Card>
            )}

            {isOrganizer && canRecordResult && (
              <Card
                title={isCorrection ? 'Corregir resultado' : 'Registrar resultado'}
                description={
                  isCorrection
                    ? 'La corrección reemplaza el marcador, los penales y todos los eventos registrados.'
                    : 'Al registrar el resultado el partido queda en estado Jugado. Registre un evento por cada gol y por cada tarjeta.'
                }
              >
                {isCorrection && (
                  <Alert kind="warning" className="mb-4">
                    Al corregir el resultado se recalculará la tabla de posiciones y las sanciones derivadas de los eventos.
                  </Alert>
                )}
                <QueryState
                  loading={homeRoster.loading || awayRoster.loading}
                  error={homeRoster.error ?? awayRoster.error}
                  onRetry={() => {
                    homeRoster.refetch()
                    awayRoster.refetch()
                  }}
                  inline
                >
                  <ResultForm
                    // Remount after each recorded/corrected result so the form reflects the saved data.
                    key={`${match.id}-${match.status}-${match.events.map((event) => event.id).join(',')}`}
                    match={match}
                    rosters={{ home: homeRoster.data?.members ?? [], away: awayRoster.data?.members ?? [] }}
                    initial={isCorrection ? resultValuesFromMatch(match) : undefined}
                    loading={result.loading}
                    error={result.error}
                    fieldErrors={result.fieldErrors}
                    onSubmit={(payload) =>
                      result
                        .mutate(payload)
                        .then((updated) => {
                          query.setData(updated)
                          toast.success(isCorrection ? 'Resultado corregido.' : 'Resultado registrado.')
                        })
                        .catch(() => undefined)
                    }
                  />
                </QueryState>
              </Card>
            )}

            {isOrganizer && !canRecordResult && (
              <Card title="Resultado">
                <p className="text-sm text-stone-500">
                  {match.status === 'CANCELLED'
                    ? 'El partido fue cancelado; no admite resultado.'
                    : 'El resultado de este partido ya no se puede registrar ni corregir.'}
                </p>
              </Card>
            )}
          </div>

          <CancelMatchDialog
            key={`${match.id}-${cancelOpen}`}
            open={cancelOpen}
            match={match}
            loading={cancel.loading}
            error={cancel.error}
            onClose={() => setCancelOpen(false)}
            onConfirm={(input) =>
              cancel
                .mutate(input)
                .then((updated) => {
                  // The contract returns the cancelled match; fall back to a refetch on a 204.
                  if (updated) query.setData(updated)
                  else void query.refetch()
                  toast.success('Partido cancelado.')
                  setCancelOpen(false)
                })
                .catch(() => undefined)
            }
          />
        </>
      )}
    </QueryState>
  )
}
