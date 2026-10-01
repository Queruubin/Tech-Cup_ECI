import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '@/components/atoms/Button'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { ConfirmDialog } from '@/components/molecules/Modal'
import { EmptyState } from '@/components/molecules/EmptyState'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { useTeam } from '@/features/teams/hooks/useTeams'
import { useRegistrations, useTournament, useTournamentMatches } from '@/features/tournaments/hooks/useTournaments'
import { errorMessage } from '@/lib/api'
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
import { KNOCKOUT_REDRAW_WARNING, approvedTeamOptions, knockoutDrawnFromGroups, resultSavedMessage } from '../matchEdit'
import { resultValuesFromMatch } from '../validation'

export function MatchDetailPage() {
  const params = useParams<{ id: string }>()
  const matchId = params.id && /^\d+$/.test(params.id) ? Number(params.id) : null
  const { user, hasRole } = useAuth()
  const isOrganizer = hasRole('ORGANIZER')
  const isReferee = hasRole('REFEREE')

  const query = useMatch(matchId)
  const match = query.data

  const tournament = useTournament(isOrganizer && match ? match.tournamentId : null)
  const inProgress = tournament.data?.status === 'IN_PROGRESS'
  // While the tournament is in progress the organizer may edit any match (played or not): past dates
  // record when it was actually played; the server locks the teams once the match was played.
  const editable = isOrganizer && !!match && inProgress
  // Cancelling (e.g. NO_SHOW) happens after kick-off too: the server requires a SCHEDULED match of a
  // tournament in progress.
  const cancellable = editable && match.status === 'SCHEDULED'
  // The server decides when a result can be recorded/corrected and when the match can be reopened.
  const canRecordResult = !!match && match.resultEditable
  const reopenable = isOrganizer && !!match && match.reopenable
  const isCorrection = !!match && match.status === 'PLAYED'

  const [editing, setEditing] = useState(false)
  const [cancelOpen, setCancelOpen] = useState(false)
  const [reopenOpen, setReopenOpen] = useState(false)

  const referees = useReferees(isOrganizer && !!match)
  const registrations = useRegistrations(match ? match.tournamentId : null, editable && editing)
  const homeRoster = useTeam(isOrganizer && canRecordResult && match ? match.homeTeam.id : null)
  const awayRoster = useTeam(isOrganizer && canRecordResult && match ? match.awayTeam.id : null)
  // Group results may still be corrected/reopened after the knockout was drawn from the standings, but
  // the server does not re-draw those pairings: the organizer must be warned.
  const tournamentMatches = useTournamentMatches(
    match ? match.tournamentId : null,
    '',
    isOrganizer && !!match && match.phase === 'GROUP' && (canRecordResult || reopenable),
  )
  const knockoutDrawn = !!match && knockoutDrawnFromGroups(match, tournamentMatches.data)

  const update = useMutation((payload: UpdateMatchRequest) => competitionApi.updateMatch(matchId as number, payload))
  const cancel = useMutation((input: CancelMatchInput) =>
    competitionApi.cancelMatch(matchId as number, input.reason, input.winnerTeamId),
  )
  const result = useMutation((payload: MatchResultRequest) => competitionApi.recordResult(matchId as number, payload))
  const reopen = useMutation(async () => {
    const reopened = await competitionApi.reopenMatch(matchId as number)
    query.setData(reopened)
    await query.refetch()
    return reopened
  })

  const confirmReopen = () =>
    reopen
      .mutate()
      .then(() => {
        toast.success('Partido reabierto.')
        setEditing(false)
      })
      .catch((cause: unknown) => toast.error(errorMessage(cause, 'No fue posible reabrir el partido.')))
      .finally(() => setReopenOpen(false))

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
                    ? 'Mientras el torneo esté en curso puede ajustar la fecha, la cancha, el árbitro y los equipos del partido.'
                    : 'La programación solo se puede editar mientras el torneo esté en curso.'
                }
                actions={
                  (editable || reopenable) && (
                    <div className="flex flex-wrap gap-2">
                      {editable && (
                        <Button size="sm" variant="outline" onClick={() => setEditing((value) => !value)}>
                          {editing ? 'Cerrar edición' : 'Editar'}
                        </Button>
                      )}
                      {reopenable && (
                        <Button size="sm" variant="outline" onClick={() => setReopenOpen(true)}>
                          Reabrir partido
                        </Button>
                      )}
                      {cancellable && (
                        <Button size="sm" variant="danger" onClick={() => setCancelOpen(true)}>
                          Cancelar partido
                        </Button>
                      )}
                    </div>
                  )
                }
              >
                <QueryState loading={tournament.loading} error={tournament.error} onRetry={tournament.refetch} inline>
                  {editable && editing ? (
                    <QueryState
                      loading={referees.loading || registrations.loading}
                      error={referees.error ?? registrations.error}
                      onRetry={() => {
                        referees.refetch()
                        registrations.refetch()
                      }}
                      inline
                    >
                      <MatchEditForm
                        // Remount when the saved match changes so the form reflects the server state.
                        key={`${match.id}-${match.status}-${match.homeTeam.id}-${match.awayTeam.id}-${match.scheduledAt ?? ''}`}
                        match={match}
                        venues={tournament.data?.venues ?? []}
                        referees={referees.data ?? []}
                        teams={approvedTeamOptions(registrations.data ?? [], [match.homeTeam, match.awayTeam])}
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
                        ? match.teamsEditable
                          ? 'Seleccione “Editar” para modificar la fecha, la cancha, el árbitro o los equipos.'
                          : 'Seleccione “Editar” para modificar la fecha, la cancha o el árbitro. Reabra el partido para cambiar los equipos.'
                        : reopenable
                          ? 'Puede reabrir el partido para dejarlo nuevamente programado.'
                          : 'El torneo no está en curso: la programación de este partido ya no se puede modificar.'}
                    </p>
                  )}
                </QueryState>
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
                {knockoutDrawn && (
                  <Alert kind="warning" className="mb-4">
                    {KNOCKOUT_REDRAW_WARNING}
                  </Alert>
                )}
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
                    // Remount after each recorded/corrected result or team change so the form reflects the
                    // saved data (and drops players of a replaced team).
                    key={`${match.id}-${match.status}-${match.homeTeam.id}-${match.awayTeam.id}-${match.events.map((event) => event.id).join(',')}`}
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
                          const message = resultSavedMessage(match.phase, isCorrection, knockoutDrawn)
                          if (knockoutDrawn) toast.warning(message)
                          else toast.success(message)
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
                    ? reopenable
                      ? 'El partido fue cancelado. Reábralo para poder registrar un resultado.'
                      : 'El partido fue cancelado; no admite resultado.'
                    : 'El resultado de este partido solo se puede registrar o corregir mientras el torneo esté en curso.'}
                </p>
              </Card>
            )}
          </div>

          <ConfirmDialog
            open={reopenOpen}
            title="Reabrir partido"
            description={
              match.status === 'CANCELLED'
                ? 'El partido volverá a quedar programado: se eliminarán la cancelación y el ganador por no presentación (walkover), y se recalculará la tabla de posiciones.'
                : 'El partido volverá a quedar programado: se eliminarán el marcador, los penales, los goles y las tarjetas registrados, y se recalculará la tabla de posiciones.'
            }
            confirmLabel="Reabrir"
            danger
            loading={reopen.loading}
            onConfirm={confirmReopen}
            onCancel={() => setReopenOpen(false)}
          >
            {knockoutDrawn && <Alert kind="warning">{KNOCKOUT_REDRAW_WARNING}</Alert>}
          </ConfirmDialog>

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
