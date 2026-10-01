import { useState, type FormEvent } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { Select, type SelectOption } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import type { MatchResponse, UpdateMatchRequest, UserResponse, VenueResponse } from '@/types/api'
import {
  NO_CHANGES_MESSAGE,
  PAST_DATE_HINT,
  hasMatchChanges,
  isPastDateTime,
  matchEditValuesFromMatch,
  refereeOptions,
  teamsSelectState,
  toUpdateMatchRequest,
  validateMatchEdit,
  type MatchEditErrors,
  type MatchEditValues,
} from '../matchEdit'

export interface MatchEditFormProps {
  match: MatchResponse
  /** Venues of the tournament the match belongs to. */
  venues: VenueResponse[]
  /** All referees (`GET /organizer/referees`); only ACTIVE ones are offered, plus the assigned one. */
  referees: UserResponse[]
  /** APPROVED teams of the tournament (see `approvedTeamOptions`). */
  teams: SelectOption[]
  loading: boolean
  error: string | null
  fieldErrors: Record<string, string>
  onSubmit: (payload: UpdateMatchRequest) => void
  onCancel?: () => void
}

/**
 * Organizer form for a match of a tournament in progress: date/time (past dates allowed to record
 * when it was actually played), venue, referee and, while the match is not played, the teams.
 */
export function MatchEditForm({ match, venues, referees, teams, loading, error, fieldErrors, onSubmit, onCancel }: MatchEditFormProps) {
  const [values, setValues] = useState<MatchEditValues>(() => matchEditValuesFromMatch(match))
  const [localErrors, setLocalErrors] = useState<MatchEditErrors>({})
  const [noChanges, setNoChanges] = useState(false)

  const venueOptions = venues.map((venue) => ({ value: String(venue.id), label: venue.name }))
  const refereeSelectOptions = refereeOptions(referees, match.referee)
  const teamsState = teamsSelectState(match)
  const past = isPastDateTime(values.scheduledAt)

  const set = (field: keyof MatchEditValues) => (value: string) => {
    setNoChanges(false)
    setValues((previous) => ({ ...previous, [field]: value }))
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const errors = validateMatchEdit(values)
    setLocalErrors(errors)
    if (Object.keys(errors).length > 0) return
    const payload = toUpdateMatchRequest(match, values)
    if (!hasMatchChanges(payload)) {
      setNoChanges(true)
      return
    }
    onSubmit(payload)
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
      {error && <Alert kind="error">{error}</Alert>}
      {noChanges && <Alert kind="info">{NO_CHANGES_MESSAGE}</Alert>}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <FormField
          label="Equipo local"
          error={localErrors.homeTeamId ?? fieldErrors.homeTeamId}
          hint={teamsState.hint ?? undefined}
        >
          <Select
            options={teams}
            value={values.homeTeamId}
            onChange={(event) => set('homeTeamId')(event.target.value)}
            disabled={teamsState.disabled}
          />
        </FormField>
        <FormField
          label="Equipo visitante"
          error={localErrors.awayTeamId ?? fieldErrors.awayTeamId}
          hint={teamsState.hint ?? undefined}
        >
          <Select
            options={teams}
            value={values.awayTeamId}
            onChange={(event) => set('awayTeamId')(event.target.value)}
            disabled={teamsState.disabled}
          />
        </FormField>
      </div>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <FormField label="Fecha y hora" error={localErrors.scheduledAt ?? fieldErrors.scheduledAt}>
          <Input type="datetime-local" value={values.scheduledAt} onChange={(event) => set('scheduledAt')(event.target.value)} />
        </FormField>
        {/* An empty select means "keep the current value": the API cannot unassign a venue or referee. */}
        <FormField
          label="Cancha"
          error={fieldErrors.venueId}
          hint={venues.length === 0 ? 'El torneo no tiene canchas registradas.' : 'Deje “Sin cambios” para conservar la actual.'}
        >
          <Select
            options={venueOptions}
            placeholder="Sin cambios"
            value={values.venueId}
            onChange={(event) => set('venueId')(event.target.value)}
            disabled={venues.length === 0}
          />
        </FormField>
        <FormField
          label="Árbitro"
          error={fieldErrors.refereeId}
          hint={refereeSelectOptions.length === 0 ? 'No hay árbitros activos registrados.' : 'Deje “Sin cambios” para conservar el actual.'}
        >
          <Select
            options={refereeSelectOptions}
            placeholder="Sin cambios"
            value={values.refereeId}
            onChange={(event) => set('refereeId')(event.target.value)}
            disabled={refereeSelectOptions.length === 0}
          />
        </FormField>
      </div>
      {past && <Alert kind="info">{PAST_DATE_HINT}</Alert>}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        {onCancel && (
          <Button type="button" variant="outline" onClick={onCancel} disabled={loading}>
            Cancelar
          </Button>
        )}
        <Button type="submit" loading={loading}>
          Guardar cambios
        </Button>
      </div>
    </form>
  )
}
