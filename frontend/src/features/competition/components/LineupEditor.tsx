import { useState, type FormEvent } from 'react'
import { Avatar } from '@/components/atoms/Avatar'
import { Button } from '@/components/atoms/Button'
import { Select } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import { LineupPitch } from '@/components/organisms/LineupPitch'
import { cn } from '@/lib/cn'
import { FORMATION_LABELS, POSITION_LABELS, toOptions } from '@/lib/labels'
import { FORMATIONS, type Formation, type LineupPlayer, type TeamMember, type UpsertLineupRequest } from '@/types/api'
import { LINEUP_STARTERS, validateLineup } from '../validation'

const FORMATION_OPTIONS = toOptions(FORMATIONS, FORMATION_LABELS)
export const DEFAULT_FORMATION: Formation = 'F_2_3_1'

/** Projects roster members onto the shape the pitch organism expects. */
export function toLineupPlayers(members: TeamMember[], starterIds: readonly number[]): LineupPlayer[] {
  return members
    .filter((member) => starterIds.includes(member.userId))
    .map((member) => ({
      userId: member.userId,
      fullName: member.fullName,
      position: member.position,
      jerseyNumber: member.jerseyNumber,
    }))
}

export interface LineupEditorProps {
  members: TeamMember[]
  initialFormation?: Formation
  initialStarterIds?: number[]
  loading: boolean
  error: string | null
  onSubmit: (payload: UpsertLineupRequest) => void
}

/** Captain form: pick the formation and exactly seven starters, with a live pitch preview. */
export function LineupEditor({
  members,
  initialFormation = DEFAULT_FORMATION,
  initialStarterIds = [],
  loading,
  error,
  onSubmit,
}: LineupEditorProps) {
  const [formation, setFormation] = useState<Formation>(initialFormation)
  const [starterIds, setStarterIds] = useState<number[]>(initialStarterIds)
  const [validationError, setValidationError] = useState<string | undefined>(undefined)

  const toggle = (userId: number) => {
    const next = starterIds.includes(userId) ? starterIds.filter((id) => id !== userId) : [...starterIds, userId]
    setStarterIds(next)
    // Re-validate only once the captain has already seen an error, to avoid nagging while picking.
    if (validationError) setValidationError(validateLineup(next))
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const problem = validateLineup(starterIds)
    setValidationError(problem)
    if (problem) return
    onSubmit({ formation, starterIds })
  }

  const remaining = LINEUP_STARTERS - starterIds.length
  const starters = toLineupPlayers(members, starterIds)

  return (
    <form onSubmit={handleSubmit} className="grid grid-cols-1 gap-6 lg:grid-cols-2" noValidate>
      <div className="flex flex-col gap-4">
        {error && <Alert kind="error">{error}</Alert>}
        <FormField label="Formación" hint="Define cómo se distribuyen los jugadores de campo.">
          <Select
            options={FORMATION_OPTIONS}
            value={formation}
            onChange={(event) => setFormation(event.target.value as Formation)}
          />
        </FormField>

        <div>
          <div className="mb-2 flex items-center justify-between">
            <h4 className="text-sm font-semibold text-ink">Titulares</h4>
            <span className={cn('text-xs font-medium', remaining === 0 ? 'text-green-700' : 'text-stone-500')}>
              {starterIds.length} / {LINEUP_STARTERS} seleccionados
            </span>
          </div>
          {members.length === 0 ? (
            <p className="text-sm text-stone-500">El equipo no tiene integrantes registrados.</p>
          ) : (
            <ul className="divide-y divide-stone-100 rounded-2xl border border-stone-200 bg-white">
              {members.map((member) => {
                const selected = starterIds.includes(member.userId)
                const blocked = !selected && remaining <= 0
                return (
                  <li key={member.userId}>
                    <label
                      className={cn(
                        'flex cursor-pointer items-center gap-3 px-4 py-2.5',
                        selected && 'bg-brand-50/60',
                        blocked && 'cursor-not-allowed opacity-50',
                      )}
                    >
                      <input
                        type="checkbox"
                        className="h-4 w-4 shrink-0 rounded border-stone-300 accent-brand-600"
                        checked={selected}
                        disabled={blocked}
                        onChange={() => toggle(member.userId)}
                      />
                      <Avatar name={member.fullName} photoFileId={member.photoFileId} size="sm" />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium text-ink">{member.fullName}</span>
                        <span className="block text-xs text-stone-500">{POSITION_LABELS[member.position]}</span>
                      </span>
                      <span className="shrink-0 text-sm font-semibold tabular-nums text-stone-500">#{member.jerseyNumber}</span>
                    </label>
                  </li>
                )
              })}
            </ul>
          )}
          {validationError && (
            <p role="alert" className="mt-2 text-xs font-medium text-brand-600">
              {validationError}
            </p>
          )}
        </div>

        <div className="flex justify-end">
          <Button type="submit" loading={loading}>
            Guardar alineación
          </Button>
        </div>
      </div>

      <div>
        <h4 className="mb-2 text-sm font-semibold text-ink">Vista previa</h4>
        <LineupPitch formation={formation} starters={starters} />
      </div>
    </form>
  )
}
