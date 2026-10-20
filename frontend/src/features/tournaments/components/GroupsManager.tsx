import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { Select } from '@/components/atoms/Select'
import { EmptyState } from '@/components/molecules/EmptyState'
import { ConfirmDialog } from '@/components/molecules/Modal'
import { errorMessage } from '@/lib/api'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { GroupResponse } from '@/types/api'
import { tournamentsApi } from '../api'
import { NO_GROUPS_HINT, type TeamOption } from '../groupStage'

export interface GroupsManagerProps {
  tournamentId: number
  groups: GroupResponse[]
  /** Approved teams without a group. */
  unassigned: TeamOption[]
  /** Groups and assignments may change (ACTIVE or IN_PROGRESS, knockout not started). */
  groupsEditable: boolean
  /** Group matches may be created (IN_PROGRESS, knockout not started). */
  matchesEditable: boolean
  /** Reloads the groups (and the matches when matches were created). */
  onChanged: (matchesChanged: boolean) => void
}

type Pending = { kind: 'roundRobin' | 'delete'; group: GroupResponse }

/**
 * Organizer view of the groups: create a group, add the approved teams that have no group, take a
 * team out while it has no match in its group, delete an empty group, and create every missing
 * pairing of one group ("todos contra todos").
 */
export function GroupsManager({
  tournamentId,
  groups,
  unassigned,
  groupsEditable,
  matchesEditable,
  onChanged,
}: GroupsManagerProps) {
  const [selection, setSelection] = useState<Record<number, string>>({})
  const [pending, setPending] = useState<Pending | null>(null)

  const run = useMutation(async (action: () => Promise<unknown>) => action())
  // Any successful change may move a team in or out of "Equipos sin grupo", so every pending choice
  // of every group is dropped: a stale choice must never assign a team somewhere else by surprise.
  const act = (action: () => Promise<unknown>, success: string | ((result: unknown) => string), matchesChanged = false) =>
    run
      .mutate(action)
      .then((result) => {
        setSelection({})
        toast.success(typeof success === 'string' ? success : success(result))
      })
      .catch((cause: unknown) => toast.error(errorMessage(cause, 'La operación no pudo completarse.')))
      .finally(() => onChanged(matchesChanged))
  // A choice only counts while its team is still without a group (the lists may have been reloaded).
  const unassignedIds = new Set(unassigned.map((team) => String(team.id)))

  const confirmPending = () => {
    if (!pending) return
    const { kind, group } = pending
    const done =
      kind === 'delete'
        ? act(() => tournamentsApi.deleteGroup(tournamentId, group.id), `${group.name} eliminado.`)
        : act(
            () => tournamentsApi.groupRoundRobin(tournamentId, group.id),
            (created) => {
              const count = Array.isArray(created) ? created.length : 0
              return `${group.name}: ${count === 1 ? '1 partido creado' : `${count} partidos creados`}.`
            },
            true,
          )
    void done.finally(() => setPending(null))
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-stone-500">
          {groups.length === 0 ? 'Aún no hay grupos.' : `${groups.length} ${groups.length === 1 ? 'grupo' : 'grupos'}.`}
        </p>
        {groupsEditable && (
          <Button
            size="sm"
            loading={run.loading && pending === null}
            onClick={() => act(() => tournamentsApi.createGroup(tournamentId), 'Grupo creado.')}
          >
            Crear grupo
          </Button>
        )}
      </div>

      {groups.length === 0 ? (
        <EmptyState title="Sin grupos" description={NO_GROUPS_HINT} />
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {groups.map((group) => {
            const picked = selection[group.id] ?? ''
            const chosen = unassignedIds.has(picked) ? picked : ''
            const deletable = groupsEditable && group.teams.length === 0 && group.matchCount === 0
            return (
              <section
                key={group.id}
                aria-label={group.name}
                className="flex flex-col gap-3 rounded-xl border border-stone-200 p-4"
              >
                <header className="flex items-center justify-between gap-2">
                  <h3 className="font-semibold text-ink">{group.name}</h3>
                  <span className="text-xs text-stone-500">
                    {group.teams.length} {group.teams.length === 1 ? 'equipo' : 'equipos'} · {group.matchCount}{' '}
                    {group.matchCount === 1 ? 'partido' : 'partidos'}
                  </span>
                </header>
                {group.teams.length === 0 ? (
                  <p className="text-sm text-stone-500">Sin equipos.</p>
                ) : (
                  <ul className="flex flex-col divide-y divide-stone-100">
                    {group.teams.map((team) => (
                      <li key={team.id} className="flex items-center justify-between gap-2 py-1.5 text-sm">
                        <span className="min-w-0 truncate text-ink">{team.name}</span>
                        {groupsEditable && (
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={team.hasMatches || run.loading}
                            title={team.hasMatches ? 'Ya tiene partidos en este grupo.' : undefined}
                            aria-label={`Quitar ${team.name} del ${group.name}`}
                            onClick={() =>
                              act(
                                () => tournamentsApi.removeTeam(tournamentId, group.id, team.id),
                                `${team.name} salió del ${group.name}.`,
                              )
                            }
                          >
                            Quitar
                          </Button>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
                {groupsEditable && unassigned.length > 0 && (
                  <div className="flex flex-col gap-2 sm:flex-row">
                    <Select
                      aria-label={`Equipo para el ${group.name}`}
                      options={unassigned.map((team) => ({ value: String(team.id), label: team.name }))}
                      placeholder="Elija un equipo"
                      value={chosen}
                      onChange={(event) => setSelection((previous) => ({ ...previous, [group.id]: event.target.value }))}
                      className="min-w-0 flex-1"
                    />
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={!chosen || run.loading}
                      onClick={() =>
                        act(
                          () => tournamentsApi.assignTeam(tournamentId, group.id, Number(chosen)),
                          `Equipo agregado al ${group.name}.`,
                        )
                      }
                    >
                      Agregar
                    </Button>
                  </div>
                )}
                {(matchesEditable || deletable) && (
                  <div className="flex flex-wrap gap-2 border-t border-stone-100 pt-3">
                    {matchesEditable && (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={group.teams.length < 2 || run.loading}
                        title={group.teams.length < 2 ? 'El grupo necesita al menos 2 equipos.' : undefined}
                        onClick={() => setPending({ kind: 'roundRobin', group })}
                      >
                        Generar todos contra todos
                      </Button>
                    )}
                    {deletable && (
                      <Button size="sm" variant="danger" onClick={() => setPending({ kind: 'delete', group })}>
                        Eliminar grupo
                      </Button>
                    )}
                  </div>
                )}
              </section>
            )
          })}
        </div>
      )}

      <div>
        <h3 className="text-sm font-semibold text-ink">Equipos sin grupo</h3>
        {unassigned.length === 0 ? (
          <p className="mt-1 text-sm text-stone-500">Todos los equipos aprobados tienen grupo.</p>
        ) : (
          <ul className="mt-2 flex flex-wrap gap-2" aria-label="Equipos sin grupo">
            {unassigned.map((team) => (
              <li key={team.id} className="rounded-full bg-stone-100 px-3 py-1 text-xs font-medium text-stone-700">
                {team.name}
              </li>
            ))}
          </ul>
        )}
      </div>

      <ConfirmDialog
        open={pending !== null}
        title={pending?.kind === 'delete' ? 'Eliminar grupo' : 'Generar todos contra todos'}
        description={
          pending?.kind === 'delete'
            ? `¿Desea eliminar el ${pending.group.name}?`
            : pending
              ? `Se crearán solo los partidos que le faltan al ${pending.group.name} para que todos sus equipos se enfrenten una vez, a las 18:00, un día por jornada, con canchas y árbitros asignados en orden, sin repetir una cancha ni un árbitro a la misma hora (si no queda ninguno libre, se deja sin asignar). Podrá editarlos después.`
              : undefined
        }
        confirmLabel={pending?.kind === 'delete' ? 'Eliminar' : 'Generar'}
        danger={pending?.kind === 'delete'}
        loading={run.loading}
        onConfirm={confirmPending}
        onCancel={() => setPending(null)}
      />
    </div>
  )
}
