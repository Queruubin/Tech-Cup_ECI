import { useState, type FormEvent } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { Select } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import { fromDateTimeLocal, toDateTimeLocal } from '@/lib/format'
import type { MatchResponse, UpdateMatchRequest, UserResponse, VenueResponse } from '@/types/api'

export interface MatchEditFormProps {
  match: MatchResponse
  /** Venues of the tournament the match belongs to. */
  venues: VenueResponse[]
  referees: UserResponse[]
  loading: boolean
  error: string | null
  fieldErrors: Record<string, string>
  onSubmit: (payload: UpdateMatchRequest) => void
  onCancel?: () => void
}

/** Organizer form for rescheduling a match and reassigning its venue and referee. */
export function MatchEditForm({ match, venues, referees, loading, error, fieldErrors, onSubmit, onCancel }: MatchEditFormProps) {
  const [scheduledAt, setScheduledAt] = useState(toDateTimeLocal(match.scheduledAt))
  const [venueId, setVenueId] = useState(match.venue ? String(match.venue.id) : '')
  const [refereeId, setRefereeId] = useState(match.referee ? String(match.referee.id) : '')
  const [localError, setLocalError] = useState<string | null>(null)

  const venueOptions = venues.map((venue) => ({ value: String(venue.id), label: venue.name }))
  const refereeOptions = referees.map((referee) => ({ value: String(referee.id), label: referee.fullName }))

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const iso = fromDateTimeLocal(scheduledAt)
    if (scheduledAt && !iso) {
      setLocalError('Ingrese una fecha y hora válidas.')
      return
    }
    if (iso && new Date(iso).getTime() <= Date.now()) {
      setLocalError('La nueva fecha debe ser posterior al momento actual.')
      return
    }
    setLocalError(null)
    const payload: UpdateMatchRequest = {}
    if (iso) payload.scheduledAt = iso
    if (venueId) payload.venueId = Number(venueId)
    if (refereeId) payload.refereeId = Number(refereeId)
    onSubmit(payload)
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
      {error && <Alert kind="error">{error}</Alert>}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <FormField label="Fecha y hora" error={localError ?? fieldErrors.scheduledAt}>
          <Input type="datetime-local" value={scheduledAt} onChange={(event) => setScheduledAt(event.target.value)} />
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
            value={venueId}
            onChange={(event) => setVenueId(event.target.value)}
            disabled={venues.length === 0}
          />
        </FormField>
        <FormField
          label="Árbitro"
          error={fieldErrors.refereeId}
          hint={referees.length === 0 ? 'No hay árbitros registrados.' : 'Deje “Sin cambios” para conservar el actual.'}
        >
          <Select
            options={refereeOptions}
            placeholder="Sin cambios"
            value={refereeId}
            onChange={(event) => setRefereeId(event.target.value)}
            disabled={referees.length === 0}
          />
        </FormField>
      </div>
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
