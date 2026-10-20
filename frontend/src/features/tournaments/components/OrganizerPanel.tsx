import { useState } from 'react'
import { useNavigate } from 'react-router'
import { StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { FileInput } from '@/components/atoms/FileInput'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { ConfirmDialog } from '@/components/molecules/Modal'
import { errorMessage } from '@/lib/api'
import { formatDate, todayIso } from '@/lib/format'
import { TOURNAMENT_STATUS_LABELS } from '@/lib/labels'
import { MAX_UPLOAD_BYTES, PDF_ACCEPT, PDF_HINT } from '@/lib/uploads'
import { reloadOnError } from '@/lib/reloadOnError'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type {
  CreateTournamentRequest,
  TournamentResponse,
  UndoPhaseResponse,
  UpdateTournamentRequest,
  VenueResponse,
} from '@/types/api'
import { tournamentsApi } from '../api'
import { useTournamentMatches } from '../hooks/useTournaments'
import { latestPhase } from '../groupStage'
import { undoPhaseConfirmDescription, undoPhaseState, undoPhaseSuccessMessage } from '../undoPhase'
import { GroupStageSection } from './GroupStageSection'
import { HardDeleteTournamentDialog } from './HardDeleteTournamentDialog'
import { RegistrationsReview } from './RegistrationsReview'
import { RulebookLink } from './RulebookLink'
import { TournamentForm } from './TournamentForm'
import { VenueForm } from './VenueForm'
import { VenueGallery } from './VenueGallery'

type Lifecycle = 'activate' | 'start' | 'finish' | 'delete' | 'advance' | 'undo' | 'suspend' | 'resume'
type Pending = { kind: Lifecycle } | { kind: 'deleteVenue'; venue: VenueResponse }

/** Statuses an ADMIN or ORGANIZER may suspend (FINISHED is history and stays read-only). */
const SUSPENDABLE = new Set<TournamentResponse['status']>(['DRAFT', 'ACTIVE', 'IN_PROGRESS'])

export type TournamentUpdate = TournamentResponse | ((previous: TournamentResponse) => TournamentResponse)

export interface OrganizerPanelProps {
  tournament: TournamentResponse
  /** Accepts the new entity or a functional updater (used for venue add/remove to avoid stale closures). */
  onUpdated: (update: TournamentUpdate) => void
  /** ADMIN only: offers the irreversible "Eliminar torneo" (any status, the name must be typed). */
  canHardDelete?: boolean
}

const LIFECYCLE_COPY: Record<Lifecycle, { title: string; description: string; confirm: string; danger?: boolean }> = {
  activate: {
    title: 'Activar torneo',
    description: 'El torneo quedará visible y abierto a inscripciones. Requiere reglamento cargado y al menos una cancha.',
    confirm: 'Activar',
  },
  start: {
    title: 'Iniciar torneo',
    description:
      'Se puede iniciar desde la fecha inicial en adelante y con al menos 2 equipos aprobados. Los equipos inscritos quedarán bloqueados.',
    confirm: 'Iniciar',
  },
  finish: {
    title: 'Finalizar torneo',
    description:
      'Se puede finalizar cuando la fecha de cierre ya llegó o cuando la final ya se jugó, y no queden partidos programados.',
    confirm: 'Finalizar',
  },
  delete: {
    title: 'Eliminar torneo',
    description: 'Esta acción no se puede deshacer. Solo se permite mientras el torneo está en borrador.',
    confirm: 'Eliminar',
    danger: true,
  },
  advance: {
    title: 'Avanzar de fase',
    description:
      'La siguiente ronda eliminatoria se crea sola cuando se juega el último partido de la ronda actual. Use esta opción si la deshizo: todos los partidos de la ronda actual deben estar jugados o cancelados.',
    confirm: 'Avanzar',
  },
  undo: {
    title: 'Deshacer última fase',
    // Replaced at render time by `undoPhaseConfirmDescription` (depends on the phase and its matches).
    description: 'Se eliminarán los partidos de la última fase generada.',
    confirm: 'Deshacer fase',
    danger: true,
  },
  suspend: {
    title: 'Suspender torneo',
    description:
      'El torneo dejará de verse para el público y los jugadores, y no se podrá modificar nada (inscripciones, partidos, resultados ni alineaciones) hasta reanudarlo. Los equipos inscritos siguen bloqueados.',
    confirm: 'Suspender',
    danger: true,
  },
  resume: {
    title: 'Reanudar torneo',
    // Replaced at render time with the status it returns to.
    description: 'El torneo volverá al estado en que estaba antes de la suspensión.',
    confirm: 'Reanudar',
  },
}

export function OrganizerPanel({ tournament, onUpdated, canHardDelete = false }: OrganizerPanelProps) {
  const navigate = useNavigate()
  const [editing, setEditing] = useState(false)
  const [pending, setPending] = useState<Pending | null>(null)
  const [hardDeleteOpen, setHardDeleteOpen] = useState(false)
  const [rulebook, setRulebook] = useState<File | null>(null)
  // Remounts (clears) the venue form only after a successful create, never on delete.
  const [venueFormKey, setVenueFormKey] = useState(0)
  const matches = useTournamentMatches(tournament.id, '', tournament.status === 'IN_PROGRESS' || tournament.status === 'FINISHED')
  // One refresh for everything the competition set-up shows: the matches here, the groups and the
  // registrations in GroupStageSection (bumping the key reloads them). Used after any change that
  // can move a team or a match: a registration decided, a phase advanced or undone, matches created.
  const [competitionKey, setCompetitionKey] = useState(0)
  const refreshCompetition = () => {
    void matches.refetch()
    setCompetitionKey((value) => value + 1)
  }
  const competition = { refetch: refreshCompetition }
  const matchCount = matches.data?.length ?? 0
  const undo = undoPhaseState(matches.data ?? [])
  const currentPhase = latestPhase(matches.data)
  // The next knockout round is drawn automatically; by hand only after undoing it.
  const canAdvance = currentPhase !== null && currentPhase !== 'GROUP' && currentPhase !== 'FINAL'

  const isDraft = tournament.status === 'DRAFT'
  const isActive = tournament.status === 'ACTIVE'
  const isSuspended = tournament.status === 'SUSPENDED'
  // Venues are frozen once the tournament is finished, and while it is suspended.
  const venuesLocked = tournament.status === 'FINISHED' || isSuspended
  // DRAFT: every field is editable. ACTIVE: only the three dates (the server rejects the rest).
  const canEdit = isDraft || isActive
  const today = todayIso()
  const canActivate = isDraft
  const activateBlockers = [
    !tournament.rulebookFileId && 'Falta cargar el reglamento.',
    tournament.venues.length === 0 && 'Debe registrar al menos una cancha.',
  ].filter((item): item is string => typeof item === 'string')
  const startBlockers = [tournament.approvedTeams < 2 && 'Se requieren al menos 2 equipos aprobados.'].filter(
    (item): item is string => typeof item === 'string',
  )
  // Informative only: the server decides whether the start date has been reached (409 otherwise).
  const startDateHint =
    tournament.startDate > today ? `El torneo se podrá iniciar a partir del ${formatDate(tournament.startDate)}.` : null

  const update = useMutation(async (payload: CreateTournamentRequest) => {
    const body: UpdateTournamentRequest = isDraft
      ? payload
      : { startDate: payload.startDate, endDate: payload.endDate, registrationDeadline: payload.registrationDeadline }
    const updated = await tournamentsApi.update(tournament.id, body)
    onUpdated(updated)
    return updated
  })
  // Resolves with the undo outcome for 'undo' (used by its toast); `undefined` for the other actions.
  const lifecycle = useMutation(async (kind: Lifecycle): Promise<UndoPhaseResponse | undefined> => {
    switch (kind) {
      case 'activate':
        onUpdated(await tournamentsApi.activate(tournament.id))
        return
      case 'start':
        onUpdated(await tournamentsApi.start(tournament.id))
        return
      case 'finish':
        onUpdated(await tournamentsApi.finish(tournament.id))
        return
      case 'suspend':
        onUpdated(await tournamentsApi.suspend(tournament.id))
        return
      case 'resume':
        onUpdated(await tournamentsApi.resume(tournament.id))
        return
      case 'delete':
        await tournamentsApi.remove(tournament.id)
        return
      case 'advance':
        // A failure may mean the phases changed meanwhile (another tab/organizer): reload anyway.
        await reloadOnError(() => tournamentsApi.advanceMatches(tournament.id), competition)
        refreshCompetition()
        return
      case 'undo': {
        const undone = await reloadOnError(() => tournamentsApi.undoPhase(tournament.id), competition)
        refreshCompetition()
        return undone
      }
    }
  })
  const uploadRulebook = useMutation(async (file: File) => {
    const updated = await tournamentsApi.uploadRulebook(tournament.id, file)
    onUpdated(updated)
    return updated
  })
  const createVenue = useMutation(async (values: { name: string; description: string; file: File | null }) => {
    const venue = await tournamentsApi.createVenue(tournament.id, values)
    onUpdated((previous) => ({ ...previous, venues: [...previous.venues, venue] }))
    return venue
  })
  const hardDelete = useMutation((confirmName: string) => tournamentsApi.hardDelete(tournament.id, confirmName))
  const confirmHardDelete = (confirmName: string) =>
    hardDelete
      .mutate(confirmName)
      .then(() => {
        toast.success('Torneo eliminado definitivamente.')
        setHardDeleteOpen(false)
        navigate('/tournaments', { replace: true })
      })
      .catch(() => undefined)
  const deleteVenue = useMutation(async (venue: VenueResponse) => {
    await tournamentsApi.deleteVenue(tournament.id, venue.id)
    onUpdated((previous) => ({ ...previous, venues: previous.venues.filter((item) => item.id !== venue.id) }))
  })

  const confirmPending = () => {
    if (!pending) return
    if (pending.kind === 'deleteVenue') {
      deleteVenue
        .mutate(pending.venue)
        .then(() => toast.success('Cancha eliminada.'))
        .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'No fue posible eliminar la cancha.'))
        .finally(() => setPending(null))
      return
    }
    const kind = pending.kind
    lifecycle
      .mutate(kind)
      .then((outcome) => {
        const messages: Record<Lifecycle, string> = {
          activate: 'Torneo activado.',
          start: 'Torneo iniciado.',
          finish: 'Torneo finalizado.',
          delete: 'Torneo eliminado.',
          advance: 'Se generó la siguiente fase.',
          undo: 'Fase deshecha.',
          suspend: 'Torneo suspendido.',
          resume: 'Torneo reanudado.',
        }
        toast.success(
          kind === 'undo' && outcome ? undoPhaseSuccessMessage(outcome.phase, outcome.deletedMatches) : messages[kind],
        )
        if (kind === 'delete') navigate('/tournaments', { replace: true })
      })
      .catch((cause: unknown) => toast.error(errorMessage(cause, 'La operación no pudo completarse.')))
      .finally(() => setPending(null))
  }

  const pendingCopy = pending && pending.kind !== 'deleteVenue' ? LIFECYCLE_COPY[pending.kind] : null
  const resumeTarget = tournament.suspendedFromStatus ? TOURNAMENT_STATUS_LABELS[tournament.suspendedFromStatus] : null
  const pendingDescription =
    pending?.kind === 'undo' && undo.phase
      ? undoPhaseConfirmDescription(undo.phase, undo.count)
      : pending?.kind === 'resume' && resumeTarget
        ? `El torneo volverá al estado «${resumeTarget}» y quedará visible de nuevo para todos.`
        : pendingCopy?.description

  return (
    <div className="flex flex-col gap-6">
      <Card
        title="Estado del torneo"
        actions={<StatusBadge kind="tournament" value={tournament.status} size="md" />}
      >
        <div className="flex flex-wrap gap-2">
          {canEdit && (
            <Button size="sm" variant="outline" onClick={() => setEditing((value) => !value)}>
              {editing ? 'Cerrar edición' : isDraft ? 'Editar datos' : 'Editar fechas'}
            </Button>
          )}
          {isDraft && (
            <>
              <Button size="sm" onClick={() => setPending({ kind: 'activate' })} disabled={!canActivate || activateBlockers.length > 0}>
                Activar
              </Button>
              {/* An ADMIN gets the "Eliminar torneo" below, which covers drafts too. */}
              {!canHardDelete && (
                <Button size="sm" variant="danger" onClick={() => setPending({ kind: 'delete' })}>
                  Eliminar
                </Button>
              )}
            </>
          )}
          {isActive && (
            <Button size="sm" onClick={() => setPending({ kind: 'start' })} disabled={startBlockers.length > 0}>
              Iniciar torneo
            </Button>
          )}
          {tournament.status === 'IN_PROGRESS' && (
            <>
              {canAdvance && (
                <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'advance' })}>
                  Avanzar fase
                </Button>
              )}
              {matchCount > 0 && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => setPending({ kind: 'undo' })}
                  disabled={!undo.enabled || matches.refetching}
                  title={undo.reason ?? undefined}
                >
                  Deshacer última fase
                </Button>
              )}
              <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'finish' })}>
                Finalizar torneo
              </Button>
            </>
          )}
          {isSuspended && (
            <Button size="sm" onClick={() => setPending({ kind: 'resume' })}>
              Reanudar torneo
            </Button>
          )}
          {SUSPENDABLE.has(tournament.status) && (
            <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'suspend' })}>
              Suspender
            </Button>
          )}
          {canHardDelete && (
            <Button size="sm" variant="danger" onClick={() => setHardDeleteOpen(true)}>
              Eliminar torneo
            </Button>
          )}
        </div>
        {isSuspended && (
          <Alert kind="warning" className="mt-3" title="Torneo suspendido">
            No es visible para el público ni para los jugadores y no admite cambios.
            {resumeTarget && ` Al reanudarlo volverá al estado «${resumeTarget}».`}
          </Alert>
        )}
        {isDraft && activateBlockers.length > 0 && (
          <Alert kind="info" className="mt-3" title="Para activar el torneo">
            <ul className="list-disc pl-5">
              {activateBlockers.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </Alert>
        )}
        {isActive && (startBlockers.length > 0 || startDateHint) && (
          <Alert kind="info" className="mt-3" title="Para iniciar el torneo">
            <ul className="list-disc pl-5">
              {startDateHint && <li>{startDateHint}</li>}
              {startBlockers.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </Alert>
        )}
        {tournament.status === 'IN_PROGRESS' && (
          <p className="mt-3 text-xs text-stone-500">
            {matchCount === 0
              ? 'Aún no hay partidos. Cree grupos, asigne los equipos y programe los partidos más abajo.'
              : `${matchCount} ${matchCount === 1 ? 'partido registrado' : 'partidos registrados'}. Las rondas eliminatorias siguientes se crean solas al jugarse el último partido de cada ronda.`}
            {matchCount > 0 && undo.reason && ` ${undo.reason}`}
          </p>
        )}
        {editing && canEdit && (
          <div className="mt-4 border-t border-stone-100 pt-4">
            {isActive && (
              <p className="mb-3 text-xs text-stone-500">
                Con el torneo activo solo se pueden modificar las fechas; el resto de los datos queda fijo.
              </p>
            )}
            <TournamentForm
              key={tournament.id}
              editableFields={isDraft ? 'all' : 'dates'}
              initial={{
                name: tournament.name,
                startDate: tournament.startDate,
                endDate: tournament.endDate,
                registrationDeadline: tournament.registrationDeadline,
                maxTeams: tournament.maxTeams,
                fee: tournament.fee,
              }}
              loading={update.loading}
              error={update.error}
              fieldErrors={update.fieldErrors}
              submitLabel="Guardar cambios"
              onCancel={() => setEditing(false)}
              onSubmit={(payload) =>
                update
                  .mutate(payload)
                  .then(() => {
                    toast.success('Torneo actualizado.')
                    setEditing(false)
                  })
                  .catch(() => undefined)
              }
            />
          </div>
        )}
      </Card>

      {tournament.status !== 'DRAFT' && (
        <GroupStageSection
          tournament={tournament}
          matches={matches.data ?? []}
          onMatchesChanged={refreshCompetition}
          refreshKey={competitionKey}
        />
      )}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card title="Reglamento" description="PDF oficial. Se puede reemplazar mientras el torneo esté en borrador o activo.">
          <div className="flex flex-col gap-3">
            <RulebookLink fileId={tournament.rulebookFileId} tournamentName={tournament.name} />
            {(tournament.status === 'DRAFT' || tournament.status === 'ACTIVE') && (
              <>
                <FileInput accept={PDF_ACCEPT} maxBytes={MAX_UPLOAD_BYTES} value={rulebook} onChange={setRulebook} hint={PDF_HINT} />
                {uploadRulebook.error && <p className="text-xs font-medium text-brand-600">{uploadRulebook.error}</p>}
                <div>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={!rulebook}
                    loading={uploadRulebook.loading}
                    onClick={() =>
                      rulebook &&
                      uploadRulebook
                        .mutate(rulebook)
                        .then(() => {
                          toast.success('Reglamento cargado.')
                          setRulebook(null)
                        })
                        .catch(() => undefined)
                    }
                  >
                    Cargar reglamento
                  </Button>
                </div>
              </>
            )}
          </div>
        </Card>

        <Card title="Agregar cancha" description="Disponible mientras el torneo no haya finalizado.">
          {venuesLocked ? (
            <p className="text-sm text-stone-500">
              {isSuspended
                ? 'El torneo está suspendido; no se pueden agregar canchas.'
                : 'El torneo finalizó; no se pueden agregar canchas.'}
            </p>
          ) : (
            <VenueForm
              key={venueFormKey}
              loading={createVenue.loading}
              error={createVenue.error}
              fieldErrors={createVenue.fieldErrors}
              onSubmit={(values) =>
                createVenue
                  .mutate(values)
                  .then(() => {
                    toast.success('Cancha agregada.')
                    setVenueFormKey((value) => value + 1)
                  })
                  .catch(() => undefined)
              }
            />
          )}
        </Card>
      </div>

      <Card title="Canchas registradas">
        <VenueGallery
          venues={tournament.venues}
          renderActions={(venue) =>
            !venuesLocked ? (
              <Button size="sm" variant="danger" onClick={() => setPending({ kind: 'deleteVenue', venue })}>
                Eliminar
              </Button>
            ) : null
          }
        />
      </Card>

      <RegistrationsReview
        tournamentId={tournament.id}
        tournamentStatus={tournament.status}
        onDecided={() => {
          // An approval adds a team to assign; a rejection or removal drops it (and its group slot).
          refreshCompetition()
          void tournamentsApi.get(tournament.id).then(onUpdated).catch(() => undefined)
        }}
      />

      <ConfirmDialog
        open={pending !== null}
        title={pendingCopy?.title ?? 'Eliminar cancha'}
        description={
          pendingDescription ??
          (pending?.kind === 'deleteVenue' ? `¿Desea eliminar la cancha ${pending.venue.name}?` : undefined)
        }
        confirmLabel={pendingCopy?.confirm ?? 'Eliminar'}
        danger={pendingCopy?.danger ?? pending?.kind === 'deleteVenue'}
        loading={lifecycle.loading || deleteVenue.loading}
        onConfirm={confirmPending}
        onCancel={() => setPending(null)}
      />

      {canHardDelete && (
        <HardDeleteTournamentDialog
          open={hardDeleteOpen}
          tournamentName={tournament.name}
          loading={hardDelete.loading}
          error={hardDelete.error}
          onConfirm={confirmHardDelete}
          onCancel={() => {
            hardDelete.reset()
            setHardDeleteOpen(false)
          }}
        />
      )}
    </div>
  )
}
