import { useState, type FormEvent } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { Select } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import { refereeOptions } from '@/features/competition/matchEdit'
import { fromDateTimeLocal } from '@/lib/format'
import type { CreateMatchRequest, GroupResponse, UserResponse, VenueResponse } from '@/types/api'
import { groupTeamOptions } from '../groupStage'

export interface CreateMatchFormProps {
  /** Groups with at least two teams. */
  groups: GroupResponse[]
  venues: VenueResponse[]
  /** All referees; only ACTIVE ones are offered. */
  referees: UserResponse[]
  loading: boolean
  error: string | null
  fieldErrors: Record<string, string>
  onSubmit: (payload: CreateMatchRequest) => Promise<unknown>
}

interface Values {
  groupId: string
  homeTeamId: string
  awayTeamId: string
  scheduledAt: string
  venueId: string
  refereeId: string
}

type Errors = Partial<Record<keyof Values, string>>

const EMPTY: Values = { groupId: '', homeTeamId: '', awayTeamId: '', scheduledAt: '', venueId: '', refereeId: '' }

function validate(values: Values): Errors {
  const errors: Errors = {}
  if (!values.groupId) errors.groupId = 'Elija el grupo.'
  if (!values.homeTeamId) errors.homeTeamId = 'Elija el equipo local.'
  if (!values.awayTeamId) errors.awayTeamId = 'Elija el equipo visitante.'
  else if (values.awayTeamId === values.homeTeamId) errors.awayTeamId = 'El equipo local y el visitante deben ser distintos.'
  if (!values.scheduledAt) errors.scheduledAt = 'Indique la fecha y hora.'
  else if (!fromDateTimeLocal(values.scheduledAt)) errors.scheduledAt = 'Ingrese una fecha y hora válidas.'
  return errors
}

/**
 * Organizer form to create one group match: the group first, then home and away among the teams of
 * that group, the kick-off, and optionally the venue and the referee. Stays filled with the same
 * group after a success so several matches can be created in a row.
 */
export function CreateMatchForm({ groups, venues, referees, loading, error, fieldErrors, onSubmit }: CreateMatchFormProps) {
  const [values, setValues] = useState<Values>(() => ({ ...EMPTY, groupId: groups.length === 1 ? String(groups[0]?.id) : '' }))
  const [errors, setErrors] = useState<Errors>({})

  const group = groups.find((candidate) => String(candidate.id) === values.groupId)
  const teamOptions = groupTeamOptions(group)
  const venueOptions = venues.map((venue) => ({ value: String(venue.id), label: venue.name }))
  const refereeSelectOptions = refereeOptions(referees)

  const set = (field: keyof Values) => (value: string) =>
    setValues((previous) =>
      field === 'groupId' ? { ...previous, groupId: value, homeTeamId: '', awayTeamId: '' } : { ...previous, [field]: value },
    )

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const found = validate(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return
    const payload: CreateMatchRequest = {
      groupId: Number(values.groupId),
      homeTeamId: Number(values.homeTeamId),
      awayTeamId: Number(values.awayTeamId),
      scheduledAt: fromDateTimeLocal(values.scheduledAt) as string,
    }
    if (values.venueId) payload.venueId = Number(values.venueId)
    if (values.refereeId) payload.refereeId = Number(values.refereeId)
    onSubmit(payload)
      .then(() => setValues((previous) => ({ ...previous, homeTeamId: '', awayTeamId: '' })))
      .catch(() => undefined)
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate aria-label="Crear partido">
      {error && <Alert kind="error">{error}</Alert>}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <FormField label="Grupo" required error={errors.groupId ?? fieldErrors.groupId}>
          <Select
            options={groups.map((candidate) => ({ value: String(candidate.id), label: candidate.name }))}
            placeholder="Elija un grupo"
            value={values.groupId}
            onChange={(event) => set('groupId')(event.target.value)}
          />
        </FormField>
        <FormField label="Equipo local" required error={errors.homeTeamId ?? fieldErrors.homeTeamId}>
          <Select
            options={teamOptions}
            placeholder={group ? 'Elija un equipo' : 'Elija primero el grupo'}
            value={values.homeTeamId}
            onChange={(event) => set('homeTeamId')(event.target.value)}
            disabled={!group}
          />
        </FormField>
        <FormField label="Equipo visitante" required error={errors.awayTeamId ?? fieldErrors.awayTeamId}>
          <Select
            options={teamOptions.filter((option) => option.value !== values.homeTeamId)}
            placeholder={group ? 'Elija un equipo' : 'Elija primero el grupo'}
            value={values.awayTeamId}
            onChange={(event) => set('awayTeamId')(event.target.value)}
            disabled={!group}
          />
        </FormField>
      </div>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <FormField label="Fecha y hora" required error={errors.scheduledAt ?? fieldErrors.scheduledAt}>
          <Input type="datetime-local" value={values.scheduledAt} onChange={(event) => set('scheduledAt')(event.target.value)} />
        </FormField>
        <FormField
          label="Cancha"
          error={fieldErrors.venueId}
          hint={venues.length === 0 ? 'El torneo no tiene canchas registradas.' : 'Opcional.'}
        >
          <Select
            options={venueOptions}
            placeholder="Sin asignar"
            value={values.venueId}
            onChange={(event) => set('venueId')(event.target.value)}
            disabled={venues.length === 0}
          />
        </FormField>
        <FormField
          label="Árbitro"
          error={fieldErrors.refereeId}
          hint={refereeSelectOptions.length === 0 ? 'No hay árbitros activos registrados.' : 'Opcional.'}
        >
          <Select
            options={refereeSelectOptions}
            placeholder="Sin asignar"
            value={values.refereeId}
            onChange={(event) => set('refereeId')(event.target.value)}
            disabled={refereeSelectOptions.length === 0}
          />
        </FormField>
      </div>
      <div className="flex justify-end">
        <Button type="submit" loading={loading}>
          Crear partido
        </Button>
      </div>
    </form>
  )
}
