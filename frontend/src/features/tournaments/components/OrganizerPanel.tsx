import { useState } from 'react'
import { useNavigate } from 'react-router'
import { StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { FileInput } from '@/components/atoms/FileInput'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { ConfirmDialog } from '@/components/molecules/Modal'
import { formatDate, todayIso } from '@/lib/format'
import { MAX_UPLOAD_BYTES, PDF_ACCEPT, PDF_HINT } from '@/lib/uploads'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { CreateTournamentRequest, TournamentResponse, UpdateTournamentRequest, VenueResponse } from '@/types/api'
import { tournamentsApi } from '../api'
import { useTournamentMatches } from '../hooks/useTournaments'
import { RegistrationsReview } from './RegistrationsReview'
import { RulebookLink } from './RulebookLink'
import { TournamentForm } from './TournamentForm'
import { VenueForm } from './VenueForm'
import { VenueGallery } from './VenueGallery'

type Lifecycle = 'activate' | 'start' | 'finish' | 'delete' | 'generate' | 'advance'
type Pending = { kind: Lifecycle } | { kind: 'deleteVenue'; venue: VenueResponse }

export interface OrganizerPanelProps {
  tournament: TournamentResponse
  onUpdated: (tournament: TournamentResponse) => void
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
    description: 'Se permite cuando la fecha final es igual o posterior a hoy o cuando la final ya fue jugada.',
    confirm: 'Finalizar',
  },
  delete: {
    title: 'Eliminar torneo',
    description: 'Esta acción no se puede deshacer. Solo se permite mientras el torneo está en borrador.',
    confirm: 'Eliminar',
    danger: true,
  },
  generate: {
    title: 'Generar fixture',
    description:
      'Se crearán los partidos de la fase de grupos (todos contra todos) con canchas y árbitros asignados automáticamente. Solo se puede hacer una vez.',
    confirm: 'Generar',
  },
  advance: {
    title: 'Avanzar de fase',
    description: 'Todos los partidos de la fase actual deben estar jugados o cancelados. Se generará la siguiente fase eliminatoria.',
    confirm: 'Avanzar',
  },
}

export function OrganizerPanel({ tournament, onUpdated }: OrganizerPanelProps) {
  const navigate = useNavigate()
  const [editing, setEditing] = useState(false)
  const [pending, setPending] = useState<Pending | null>(null)
  const [rulebook, setRulebook] = useState<File | null>(null)
  const matches = useTournamentMatches(tournament.id, '', tournament.status === 'IN_PROGRESS' || tournament.status === 'FINISHED')
  const matchCount = matches.data?.length ?? 0

  const isDraft = tournament.status === 'DRAFT'
  const isActive = tournament.status === 'ACTIVE'
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
  const lifecycle = useMutation(async (kind: Lifecycle) => {
    switch (kind) {
      case 'activate':
        return onUpdated(await tournamentsApi.activate(tournament.id))
      case 'start':
        return onUpdated(await tournamentsApi.start(tournament.id))
      case 'finish':
        return onUpdated(await tournamentsApi.finish(tournament.id))
      case 'delete':
        await tournamentsApi.remove(tournament.id)
        return
      case 'generate':
        await tournamentsApi.generateMatches(tournament.id)
        matches.refetch()
        return
      case 'advance':
        await tournamentsApi.advanceMatches(tournament.id)
        matches.refetch()
        return
    }
  })
  const uploadRulebook = useMutation(async (file: File) => {
    const updated = await tournamentsApi.uploadRulebook(tournament.id, file)
    onUpdated(updated)
    return updated
  })
  const createVenue = useMutation(async (values: { name: string; description: string; file: File | null }) => {
    const venue = await tournamentsApi.createVenue(tournament.id, values)
    onUpdated({ ...tournament, venues: [...tournament.venues, venue] })
    return venue
  })
  const deleteVenue = useMutation(async (venue: VenueResponse) => {
    await tournamentsApi.deleteVenue(tournament.id, venue.id)
    onUpdated({ ...tournament, venues: tournament.venues.filter((item) => item.id !== venue.id) })
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
      .then(() => {
        const messages: Record<Lifecycle, string> = {
          activate: 'Torneo activado.',
          start: 'Torneo iniciado.',
          finish: 'Torneo finalizado.',
          delete: 'Torneo eliminado.',
          generate: 'Fixture generado.',
          advance: 'Se generó la siguiente fase.',
        }
        toast.success(messages[kind])
        if (kind === 'delete') navigate('/tournaments', { replace: true })
      })
      .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'La operación no pudo completarse.'))
      .finally(() => setPending(null))
  }

  const pendingCopy = pending && pending.kind !== 'deleteVenue' ? LIFECYCLE_COPY[pending.kind] : null

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
              <Button size="sm" variant="danger" onClick={() => setPending({ kind: 'delete' })}>
                Eliminar
              </Button>
            </>
          )}
          {isActive && (
            <Button size="sm" onClick={() => setPending({ kind: 'start' })} disabled={startBlockers.length > 0}>
              Iniciar torneo
            </Button>
          )}
          {tournament.status === 'IN_PROGRESS' && (
            <>
              {matchCount === 0 ? (
                <Button size="sm" onClick={() => setPending({ kind: 'generate' })} loading={matches.loading}>
                  Generar fixture
                </Button>
              ) : (
                <Button size="sm" onClick={() => setPending({ kind: 'advance' })}>
                  Avanzar fase
                </Button>
              )}
              <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'finish' })}>
                Finalizar torneo
              </Button>
            </>
          )}
        </div>
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
              ? 'Aún no se ha generado el fixture.'
              : `${matchCount} partidos registrados. Avance de fase cuando todos los partidos de la fase actual estén jugados o cancelados.`}
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
          {tournament.status === 'FINISHED' ? (
            <p className="text-sm text-stone-500">El torneo finalizó; no se pueden agregar canchas.</p>
          ) : (
            <VenueForm
              key={tournament.venues.length}
              loading={createVenue.loading}
              error={createVenue.error}
              fieldErrors={createVenue.fieldErrors}
              onSubmit={(values) =>
                createVenue
                  .mutate(values)
                  .then(() => toast.success('Cancha agregada.'))
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
            tournament.status !== 'FINISHED' ? (
              <Button size="sm" variant="danger" onClick={() => setPending({ kind: 'deleteVenue', venue })}>
                Eliminar
              </Button>
            ) : null
          }
        />
      </Card>

      <RegistrationsReview tournamentId={tournament.id} onDecided={() => tournamentsApi.get(tournament.id).then(onUpdated).catch(() => undefined)} />

      <ConfirmDialog
        open={pending !== null}
        title={pendingCopy?.title ?? 'Eliminar cancha'}
        description={
          pendingCopy?.description ??
          (pending?.kind === 'deleteVenue' ? `¿Desea eliminar la cancha ${pending.venue.name}?` : undefined)
        }
        confirmLabel={pendingCopy?.confirm ?? 'Eliminar'}
        danger={pendingCopy?.danger ?? pending?.kind === 'deleteVenue'}
        loading={lifecycle.loading || deleteVenue.loading}
        onConfirm={confirmPending}
        onCancel={() => setPending(null)}
      />
    </div>
  )
}
