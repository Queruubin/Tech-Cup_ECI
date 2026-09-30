import { useRef, useState, type FormEvent } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { Select } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import { EVENT_TYPE_LABELS, toOptions } from '@/lib/labels'
import { EVENT_TYPES, type MatchResponse, type MatchResultRequest, type TeamMember } from '@/types/api'
import {
  EMPTY_RESULT_VALUES,
  goalsFor,
  isKnockoutPhase,
  toResultRequest,
  validateResult,
  type MatchSide,
  type ResultEventDraft,
  type ResultFormValues,
} from '../validation'

const EVENT_TYPE_OPTIONS = toOptions(EVENT_TYPES, EVENT_TYPE_LABELS)

const EVENT_FIELD_PATTERN = /^events\[(\d+)\](?:\.(\w+))?$/
const EVENT_FIELD_LABELS: Record<string, string> = {
  teamId: 'Equipo',
  playerId: 'Jugador',
  type: 'Tipo',
  minute: 'Minuto',
}

/**
 * Groups backend field errors of the shape `events[n].field` (or `events[n]`) by row index, so each
 * message can be shown next to its event row. Keeps the first message per row.
 */
export function eventErrorsByIndex(fieldErrors: Record<string, string>): Record<number, string> {
  const byIndex: Record<number, string> = {}
  for (const [field, message] of Object.entries(fieldErrors)) {
    const match = EVENT_FIELD_PATTERN.exec(field)
    if (!match) continue
    const index = Number(match[1])
    if (index in byIndex) continue
    const label = match[2] ? EVENT_FIELD_LABELS[match[2]] : undefined
    byIndex[index] = label ? `${label}: ${message}` : message
  }
  return byIndex
}

export interface ResultFormProps {
  match: MatchResponse
  /** Rosters used to pick the player of each event, keyed by side. */
  rosters: Record<MatchSide, TeamMember[]>
  /** Pre-filled values (see `resultValuesFromMatch`) when correcting an already recorded result. */
  initial?: ResultFormValues
  loading: boolean
  error: string | null
  fieldErrors: Record<string, string>
  onSubmit: (payload: MatchResultRequest) => void
}

/**
 * Organizer form to register the result of a SCHEDULED match, or to correct the result of a
 * PLAYED match (when `initial` is given the submit reads "Corregir resultado").
 * Client-side rule: the goal events of each team must add up to that team's score.
 */
export function ResultForm({ match, rosters, initial, loading, error, fieldErrors, onSubmit }: ResultFormProps) {
  const correction = initial !== undefined
  const [values, setValues] = useState<ResultFormValues>(initial ?? EMPTY_RESULT_VALUES)
  const [validation, setValidation] = useState(() => validateResult(initial ?? EMPTY_RESULT_VALUES, match.phase))
  const [submitted, setSubmitted] = useState(false)
  const nextKey = useRef(1)

  const knockout = isKnockoutPhase(match.phase)
  const sideOptions = [
    { value: 'home', label: match.homeTeam.name },
    { value: 'away', label: match.awayTeam.name },
  ]

  const update = (next: ResultFormValues) => {
    setValues(next)
    if (submitted) setValidation(validateResult(next, match.phase))
  }

  const updateEvent = (key: string, patch: Partial<ResultEventDraft>) => {
    update({
      ...values,
      events: values.events.map((event) => (event.key === key ? { ...event, ...patch } : event)),
    })
  }

  const addEvent = () => {
    const key = `event-${nextKey.current++}`
    update({ ...values, events: [...values.events, { key, side: '', playerId: '', type: '', minute: '' }] })
  }

  const removeEvent = (key: string) => {
    update({ ...values, events: values.events.filter((event) => event.key !== key) })
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    const result = validateResult(values, match.phase)
    setValidation(result)
    if (!result.valid) return
    onSubmit(toResultRequest(values, { home: match.homeTeam.id, away: match.awayTeam.id }, match.phase))
  }

  // Validation messages only appear once the organizer has tried to submit: showing them on an
  // untouched, empty form would flag every field in red before any interaction.
  const errorFor = (field: 'homeScore' | 'awayScore' | 'homePenalties' | 'awayPenalties') =>
    (submitted ? validation.errors[field] : undefined) ?? fieldErrors[field]
  // Backend per-event errors arrive as `events[n].minute`, `events[n].playerId`, ... keyed by index.
  const serverEventErrors = eventErrorsByIndex(fieldErrors)

  const homeGoals = goalsFor(values.events, 'home')
  const awayGoals = goalsFor(values.events, 'away')

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-5" noValidate>
      {error && <Alert kind="error">{error}</Alert>}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <FormField label={`Goles de ${match.homeTeam.name}`} required error={errorFor('homeScore')}>
          <Input
            type="number"
            min={0}
            value={values.homeScore}
            onChange={(event) => update({ ...values, homeScore: event.target.value })}
          />
        </FormField>
        <FormField label={`Goles de ${match.awayTeam.name}`} required error={errorFor('awayScore')}>
          <Input
            type="number"
            min={0}
            value={values.awayScore}
            onChange={(event) => update({ ...values, awayScore: event.target.value })}
          />
        </FormField>
      </div>

      {knockout && (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField
            label={`Penales de ${match.homeTeam.name}`}
            error={errorFor('homePenalties')}
            hint="Obligatorio solo si el partido termina empatado."
          >
            <Input
              type="number"
              min={0}
              value={values.homePenalties}
              onChange={(event) => update({ ...values, homePenalties: event.target.value })}
            />
          </FormField>
          <FormField label={`Penales de ${match.awayTeam.name}`} error={errorFor('awayPenalties')}>
            <Input
              type="number"
              min={0}
              value={values.awayPenalties}
              onChange={(event) => update({ ...values, awayPenalties: event.target.value })}
            />
          </FormField>
        </div>
      )}

      <div>
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
          <div>
            <h4 className="text-sm font-semibold text-ink">Eventos del partido</h4>
            <p className="text-xs text-stone-500">
              Goles registrados: {match.homeTeam.name} {homeGoals} · {match.awayTeam.name} {awayGoals}. Deben coincidir con el
              marcador.
            </p>
          </div>
          <Button type="button" size="sm" variant="outline" onClick={addEvent}>
            Agregar evento
          </Button>
        </div>

        {values.events.length === 0 ? (
          <p className="rounded-xl border border-dashed border-stone-300 px-4 py-6 text-center text-sm text-stone-500">
            Aún no hay eventos. Agregue un evento por cada gol y por cada tarjeta.
          </p>
        ) : (
          <ul className="flex flex-col gap-3">
            {values.events.map((event, index) => {
              const roster = event.side === 'away' ? rosters.away : event.side === 'home' ? rosters.home : []
              const playerOptions = roster.map((member) => ({
                value: String(member.userId),
                label: `#${member.jerseyNumber} ${member.fullName}`,
              }))
              const rowError = (submitted ? validation.eventErrors[event.key] : undefined) ?? serverEventErrors[index]
              return (
                <li key={event.key} className="rounded-xl border border-stone-200 bg-stone-50 p-3">
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-[1fr_1fr_1fr_5rem_auto] sm:items-end">
                    <FormField label="Equipo">
                      <Select
                        options={sideOptions}
                        placeholder="Seleccione"
                        value={event.side}
                        onChange={(change) =>
                          updateEvent(event.key, { side: change.target.value as MatchSide | '', playerId: '' })
                        }
                      />
                    </FormField>
                    <FormField label="Jugador">
                      <Select
                        options={playerOptions}
                        placeholder={event.side ? 'Seleccione' : 'Elija el equipo'}
                        value={event.playerId}
                        disabled={!event.side}
                        onChange={(change) => updateEvent(event.key, { playerId: change.target.value })}
                      />
                    </FormField>
                    <FormField label="Tipo">
                      <Select
                        options={EVENT_TYPE_OPTIONS}
                        placeholder="Seleccione"
                        value={event.type}
                        onChange={(change) => updateEvent(event.key, { type: change.target.value as ResultEventDraft['type'] })}
                      />
                    </FormField>
                    <FormField label="Minuto">
                      <Input
                        type="number"
                        min={0}
                        value={event.minute}
                        onChange={(change) => updateEvent(event.key, { minute: change.target.value })}
                      />
                    </FormField>
                    <Button type="button" size="sm" variant="danger" onClick={() => removeEvent(event.key)}>
                      Quitar
                    </Button>
                  </div>
                  {rowError && (
                    <p role="alert" className="mt-2 text-xs font-medium text-brand-600">
                      {rowError}
                    </p>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </div>

      <div className="flex justify-end">
        <Button type="submit" loading={loading}>
          {correction ? 'Corregir resultado' : 'Registrar resultado'}
        </Button>
      </div>
    </form>
  )
}
