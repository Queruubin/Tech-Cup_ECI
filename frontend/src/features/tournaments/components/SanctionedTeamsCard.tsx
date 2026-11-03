import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { Select } from '@/components/atoms/Select'
import { Textarea } from '@/components/atoms/Textarea'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { FormField } from '@/components/molecules/FormField'
import { ConfirmDialog } from '@/components/molecules/Modal'
import { errorMessage } from '@/lib/api'
import { formatDate } from '@/lib/format'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { SanctionTeamRequest, SanctionedTeam, TournamentHistory } from '@/types/api'
import { tournamentsApi } from '../api'
import { sanctionCandidates } from '../history'

const NOTE_MAX = 500

export interface SanctionedTeamsCardProps {
  tournamentId: number
  history: TournamentHistory
  /** ADMIN / ORGANIZER: add and remove sanctioned teams. */
  canManage: boolean
  onChanged: () => void
}

/**
 * Sanctioned teams of a finished tournament. Everybody sees them (a snapshot of the team name and
 * members at the time of the sanction, kept if the team is later renamed or deleted); staff add a
 * team that played the tournament, with an optional note, or remove an entry.
 */
export function SanctionedTeamsCard({ tournamentId, history, canManage, onChanged }: SanctionedTeamsCardProps) {
  const [teamId, setTeamId] = useState('')
  const [note, setNote] = useState('')
  const [removing, setRemoving] = useState<SanctionedTeam | null>(null)
  const add = useMutation((payload: SanctionTeamRequest) => tournamentsApi.addSanction(tournamentId, payload))
  const remove = useMutation((sanctionId: number) => tournamentsApi.removeSanction(tournamentId, sanctionId))
  const candidates = sanctionCandidates(history)
  const chosen = candidates.some((team) => String(team.id) === teamId) ? teamId : ''

  const submit = () => {
    if (!chosen) return
    add
      .mutate({ teamId: Number(chosen), note: note.trim() || undefined })
      .then((created) => {
        toast.success(`${created.teamName} quedó registrado como equipo sancionado.`)
        setTeamId('')
        setNote('')
        onChanged()
      })
      .catch(() => undefined)
  }

  const confirmRemove = () => {
    if (!removing) return
    remove
      .mutate(removing.id)
      .then(() => {
        toast.success(`Se retiró la sanción de ${removing.teamName}.`)
        onChanged()
      })
      .catch((cause: unknown) => toast.error(errorMessage(cause, 'No fue posible retirar la sanción.')))
      .finally(() => setRemoving(null))
  }

  return (
    <Card title="Equipos sancionados" description="Equipos sancionados en este torneo, con sus integrantes al momento de la sanción.">
      {history.sanctionedTeams.length === 0 ? (
        <p className="text-sm text-stone-500">No hay equipos sancionados.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-stone-100" aria-label="Equipos sancionados">
          {history.sanctionedTeams.map((sanction) => (
            <li key={sanction.id} className="flex flex-col gap-1 py-3 first:pt-0">
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <p className="font-semibold text-ink">
                    {sanction.teamName}
                    {sanction.teamId === null && <span className="ml-2 text-xs font-normal text-stone-500">(equipo eliminado)</span>}
                  </p>
                  {sanction.note && <p className="text-sm text-stone-700">{sanction.note}</p>}
                  <p className="text-xs text-stone-500">Registrado el {formatDate(sanction.createdAt)}</p>
                </div>
                {canManage && (
                  <Button
                    size="sm"
                    variant="ghost"
                    aria-label={`Retirar la sanción de ${sanction.teamName}`}
                    onClick={() => setRemoving(sanction)}
                  >
                    Retirar
                  </Button>
                )}
              </div>
              {sanction.members.length > 0 && (
                <details className="text-sm">
                  <summary className="cursor-pointer text-stone-600">Integrantes ({sanction.members.length})</summary>
                  <ul className="mt-1 flex flex-wrap gap-1.5">
                    {sanction.members.map((member, index) => (
                      <li key={`${index}-${member.fullName}`} className="rounded-full bg-stone-100 px-2.5 py-0.5 text-xs text-stone-700">
                        {member.fullName}
                        {member.captain && ' (capitán)'}
                      </li>
                    ))}
                  </ul>
                </details>
              )}
            </li>
          ))}
        </ul>
      )}

      {canManage && (
        <div className="mt-4 flex flex-col gap-3 border-t border-stone-100 pt-4">
          <h3 className="text-sm font-semibold text-ink">Agregar equipo sancionado</h3>
          {add.error && <Alert kind="error">{add.error}</Alert>}
          {candidates.length === 0 ? (
            <p className="text-sm text-stone-500">Todos los equipos del torneo ya figuran como sancionados.</p>
          ) : (
            <>
              <FormField label="Equipo">
                <Select
                  options={candidates.map((team) => ({ value: String(team.id), label: team.name }))}
                  placeholder="Elija un equipo del torneo"
                  value={chosen}
                  onChange={(event) => setTeamId(event.target.value)}
                />
              </FormField>
              <FormField label="Nota (opcional)" hint={`Máximo ${NOTE_MAX} caracteres.`}>
                <Textarea value={note} maxLength={NOTE_MAX} onChange={(event) => setNote(event.target.value)} />
              </FormField>
              <div>
                <Button size="sm" disabled={!chosen} loading={add.loading} onClick={submit}>
                  Agregar sanción
                </Button>
              </div>
            </>
          )}
        </div>
      )}

      <ConfirmDialog
        open={removing !== null}
        title="Retirar sanción"
        description={removing ? `¿Desea retirar a ${removing.teamName} de los equipos sancionados de este torneo?` : undefined}
        confirmLabel="Retirar"
        danger
        loading={remove.loading}
        onConfirm={confirmRemove}
        onCancel={() => setRemoving(null)}
      />
    </Card>
  )
}
