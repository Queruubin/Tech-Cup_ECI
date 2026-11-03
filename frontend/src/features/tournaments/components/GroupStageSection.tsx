import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { ConfirmDialog } from '@/components/molecules/Modal'
import { QueryState } from '@/components/molecules/QueryState'
import { useReferees } from '@/features/competition/hooks/useCompetition'
import { errorMessage } from '@/lib/api'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { CreateMatchRequest, MatchResponse, TournamentResponse } from '@/types/api'
import { tournamentsApi } from '../api'
import { NO_GROUPS_HINT, approvedTeams, generateAllState, knockoutStarted, unassignedTeams } from '../groupStage'
import { useGroups, useRegistrations } from '../hooks/useTournaments'
import { CreateMatchForm } from './CreateMatchForm'
import { GroupsManager } from './GroupsManager'
import { KnockoutBuilderDialog } from './KnockoutBuilderDialog'

export interface GroupStageSectionProps {
  tournament: TournamentResponse
  /** Every match of the tournament (empty before it starts). */
  matches: MatchResponse[]
  /**
   * Called after this section created matches (one by one, all against all, the knockout): the
   * owner reloads the matches and bumps {@link GroupStageSectionProps.refreshKey}.
   */
  onMatchesChanged: () => void
  /**
   * Bumped by the owner whenever the groups or the registrations may have changed elsewhere (a
   * registration decided, a phase undone or advanced, matches created): both are reloaded,
   * keeping the current data on screen meanwhile.
   */
  refreshKey: number
}

/**
 * Organizer container for the competition set-up: groups and their teams, group matches generated
 * for every group at once ("Generar partidos", hybrid fixture), created by hand (or all against all
 * inside one group) or deleted while they have no result, and the "Armar eliminatoria" dialog. Actions follow
 * the tournament status: groups change while ACTIVE or IN_PROGRESS, matches and the knockout only
 * while IN_PROGRESS, and nothing once the knockout started or while the tournament is suspended.
 */
export function GroupStageSection({ tournament, matches, onMatchesChanged, refreshKey }: GroupStageSectionProps) {
  const inProgress = tournament.status === 'IN_PROGRESS'
  const knockout = knockoutStarted(matches)
  const groupsEditable = (tournament.status === 'ACTIVE' || inProgress) && !knockout
  const matchesEditable = inProgress && !knockout

  const groups = useGroups(tournament.id)
  const registrations = useRegistrations(tournament.id)
  const referees = useReferees(matchesEditable)
  const [knockoutOpen, setKnockoutOpen] = useState(false)
  // Remounts the knockout dialog so every opening starts from a clean draft.
  const [knockoutKey, setKnockoutKey] = useState(0)
  const [generateOpen, setGenerateOpen] = useState(false)

  const createMatch = useMutation((payload: CreateMatchRequest) => tournamentsApi.createMatch(tournament.id, payload))
  const generateAll = useMutation(() => tournamentsApi.generateGroupMatches(tournament.id))

  const groupList = groups.data ?? []
  const playable = groupList.filter((group) => group.teams.length >= 2)
  const teams = approvedTeams(registrations.data ?? [])
  const unassigned = unassignedTeams(registrations.data ?? [], groupList)
  const generate = generateAllState(tournament.status, matches, groupList, unassigned)
  const showGenerate = tournament.status !== 'FINISHED' && !groups.loading && !registrations.loading

  const confirmGenerate = () =>
    generateAll
      .mutate()
      .then((outcome) => {
        if (outcome.created > 0) toast.success(outcome.message)
        else toast.info(outcome.message)
      })
      .catch((cause: unknown) => toast.error(errorMessage(cause, 'No fue posible generar los partidos.')))
      .finally(() => {
        setGenerateOpen(false)
        onMatchesChanged()
      })

  // `refetch` is stable, so this runs only when the key changes (never on mount: the queries just loaded).
  const { refetch: refetchGroups } = groups
  const { refetch: refetchRegistrations } = registrations
  const lastRefreshKey = useRef(refreshKey)
  useEffect(() => {
    if (lastRefreshKey.current === refreshKey) return
    lastRefreshKey.current = refreshKey
    void refetchGroups()
    void refetchRegistrations()
  }, [refreshKey, refetchGroups, refetchRegistrations])

  // New matches change the groups too (match counts, teams that can no longer move): the owner's
  // refresh reloads them; a change of the groups alone only needs the groups.
  const reload = (matchesChanged: boolean) => {
    if (matchesChanged) onMatchesChanged()
    else void groups.refetch()
  }

  return (
    <div className="flex flex-col gap-6">
      <Card
        title="Grupos"
        description="Reparta los equipos aprobados en grupos (pueden tener tamaños distintos). Un equipo con partidos en su grupo ya no se puede mover."
      >
        <QueryState
          loading={groups.loading || registrations.loading}
          error={groups.error ?? registrations.error}
          onRetry={() => {
            void groups.refetch()
            void registrations.refetch()
          }}
          inline
        >
          {knockout && (
            <Alert kind="info" className="mb-4">
              La eliminatoria ya comenzó: los grupos quedan como están. Para cambiarlos, deshaga las fases eliminatorias.
            </Alert>
          )}
          <GroupsManager
            tournamentId={tournament.id}
            groups={groupList}
            unassigned={unassigned}
            matches={matches}
            groupsEditable={groupsEditable}
            matchesEditable={matchesEditable}
            onChanged={reload}
          />
        </QueryState>
      </Card>

      {showGenerate && (
        <Card
          title="Partidos de grupo"
          description="Genere de una vez los partidos de todos los grupos (todos contra todos dentro de cada grupo). Después puede eliminar, crear o editar partidos a mano."
          actions={
            <Button size="sm" disabled={!generate.enabled} loading={generateAll.loading} onClick={() => setGenerateOpen(true)}>
              Generar partidos
            </Button>
          }
        >
          <p className="text-sm text-stone-500" data-testid="generate-matches-hint">
            {generate.reason ??
              'Se crearán solo los partidos que les falten a los grupos, a las 18:00, un día por jornada, sin repetir una cancha ni un árbitro a la misma hora.'}
          </p>
        </Card>
      )}

      {inProgress && (
        <Card title="Crear partido" description="Programe un partido entre dos equipos del mismo grupo.">
          {knockout ? (
            <p className="text-sm text-stone-500">La eliminatoria ya comenzó: no se pueden crear más partidos de grupo.</p>
          ) : playable.length === 0 ? (
            <p className="text-sm text-stone-500">{NO_GROUPS_HINT}</p>
          ) : (
            <QueryState loading={referees.loading} error={referees.error} onRetry={referees.refetch} inline>
              <CreateMatchForm
                key={playable.map((group) => `${group.id}:${group.teams.length}`).join(',')}
                groups={playable}
                venues={tournament.venues}
                referees={referees.data ?? []}
                loading={createMatch.loading}
                error={createMatch.error}
                fieldErrors={createMatch.fieldErrors}
                onSubmit={(payload) =>
                  createMatch.mutate(payload).then((created) => {
                    toast.success(`Partido creado: ${created.homeTeam.name} vs ${created.awayTeam.name}.`)
                    reload(true)
                  })
                }
              />
            </QueryState>
          )}
        </Card>
      )}

      {inProgress && (
        <Card
          title="Eliminatoria"
          description="Cuando terminen los partidos de grupo, arme la primera ronda eliminatoria a partir de una sugerencia que puede editar."
          actions={
            !knockout && (
              <Button
                size="sm"
                onClick={() => {
                  setKnockoutKey((value) => value + 1)
                  setKnockoutOpen(true)
                }}
              >
                Armar eliminatoria
              </Button>
            )
          }
        >
          <p className="text-sm text-stone-500">
            {knockout
              ? 'La eliminatoria ya fue armada. Las rondas siguientes se crean solas; para rehacerla, deshaga sus fases.'
              : 'Requiere que no queden partidos de grupo programados.'}
          </p>
        </Card>
      )}

      <ConfirmDialog
        open={generateOpen}
        title="Generar partidos"
        description="Se crearán los partidos que le faltan a cada grupo para que todos sus equipos se enfrenten una vez, a las 18:00, un día por jornada, con canchas y árbitros asignados en orden, sin repetir una cancha ni un árbitro a la misma hora (si no queda ninguno libre, se deja sin asignar). Los partidos que ya existen no se tocan. Podrá eliminarlos o editarlos después."
        confirmLabel="Generar"
        loading={generateAll.loading}
        onConfirm={confirmGenerate}
        onCancel={() => setGenerateOpen(false)}
      />

      {inProgress && !knockout && (
        <KnockoutBuilderDialog
          key={knockoutKey}
          open={knockoutOpen}
          tournamentId={tournament.id}
          teams={teams}
          onClose={() => setKnockoutOpen(false)}
          onCreated={(created) => {
            toast.success(created.length === 1 ? 'Final creada.' : `Eliminatoria creada: ${created.length} partidos.`)
            setKnockoutOpen(false)
            onMatchesChanged()
          }}
        />
      )}
    </div>
  )
}
