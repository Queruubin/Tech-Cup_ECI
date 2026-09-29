import { useState, type FormEvent } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import type { CreateTournamentRequest } from '@/types/api'

export interface TournamentFormProps {
  initial?: CreateTournamentRequest
  /** `'dates'` (ACTIVE tournaments) locks every field except the three dates. */
  editableFields?: 'all' | 'dates'
  loading: boolean
  error: string | null
  fieldErrors: Record<string, string>
  submitLabel: string
  onSubmit: (payload: CreateTournamentRequest) => void
  onCancel?: () => void
}

type Errors = Partial<Record<keyof CreateTournamentRequest, string>>

const EMPTY: CreateTournamentRequest = {
  name: '',
  startDate: '',
  endDate: '',
  registrationDeadline: '',
  maxTeams: 8,
  fee: 0,
}

export function validateTournament(values: {
  name: string
  startDate: string
  endDate: string
  registrationDeadline: string
  maxTeams: string
  fee: string
}): Errors {
  const errors: Errors = {}
  if (values.name.trim().length < 3) errors.name = 'Ingrese un nombre de al menos 3 caracteres.'
  if (!values.startDate) errors.startDate = 'Ingrese la fecha inicial.'
  if (!values.endDate) errors.endDate = 'Ingrese la fecha final.'
  if (!values.registrationDeadline) errors.registrationDeadline = 'Ingrese el cierre de inscripciones.'
  if (values.startDate && values.endDate && values.endDate < values.startDate) {
    errors.endDate = 'La fecha final debe ser igual o posterior a la fecha inicial.'
  }
  if (values.registrationDeadline && values.startDate && values.registrationDeadline > values.startDate) {
    errors.registrationDeadline = 'El cierre de inscripciones debe ser anterior o igual a la fecha inicial.'
  }
  const maxTeams = Number(values.maxTeams)
  if (!Number.isInteger(maxTeams) || maxTeams < 2) errors.maxTeams = 'Indique al menos 2 equipos.'
  const fee = Number(values.fee)
  if (values.fee.trim() === '' || Number.isNaN(fee) || fee < 0) errors.fee = 'Ingrese un costo válido (0 o más).'
  return errors
}

export function TournamentForm({
  initial = EMPTY,
  editableFields = 'all',
  loading,
  error,
  fieldErrors,
  submitLabel,
  onSubmit,
  onCancel,
}: TournamentFormProps) {
  const datesOnly = editableFields === 'dates'
  const [name, setName] = useState(initial.name)
  const [startDate, setStartDate] = useState(initial.startDate)
  const [endDate, setEndDate] = useState(initial.endDate)
  const [registrationDeadline, setRegistrationDeadline] = useState(initial.registrationDeadline)
  const [maxTeams, setMaxTeams] = useState(String(initial.maxTeams))
  const [fee, setFee] = useState(String(initial.fee))
  const [errors, setErrors] = useState<Errors>({})

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const next = validateTournament({ name, startDate, endDate, registrationDeadline, maxTeams, fee })
    setErrors(next)
    if (Object.keys(next).length > 0) return
    onSubmit({
      name: name.trim(),
      startDate,
      endDate,
      registrationDeadline,
      maxTeams: Number(maxTeams),
      fee: Number(fee),
    })
  }

  const errorFor = (key: keyof CreateTournamentRequest) => errors[key] ?? fieldErrors[key]

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
      {error && <Alert kind="error">{error}</Alert>}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <FormField label="Nombre" required error={errorFor('name')} className="sm:col-span-2">
          <Input value={name} disabled={datesOnly} onChange={(event) => setName(event.target.value)} />
        </FormField>
        <FormField label="Fecha inicial" required error={errorFor('startDate')}>
          <Input type="date" value={startDate} onChange={(event) => setStartDate(event.target.value)} />
        </FormField>
        <FormField label="Fecha final" required error={errorFor('endDate')}>
          <Input type="date" value={endDate} onChange={(event) => setEndDate(event.target.value)} />
        </FormField>
        <FormField label="Cierre de inscripciones" required error={errorFor('registrationDeadline')}>
          <Input type="date" value={registrationDeadline} onChange={(event) => setRegistrationDeadline(event.target.value)} />
        </FormField>
        <FormField label="Cantidad máxima de equipos" required error={errorFor('maxTeams')}>
          <Input type="number" min={2} value={maxTeams} disabled={datesOnly} onChange={(event) => setMaxTeams(event.target.value)} />
        </FormField>
        <FormField label="Costo de inscripción (COP)" required error={errorFor('fee')}>
          <Input type="number" min={0} step={1000} value={fee} disabled={datesOnly} onChange={(event) => setFee(event.target.value)} />
        </FormField>
      </div>
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        {onCancel && (
          <Button type="button" variant="outline" onClick={onCancel} disabled={loading}>
            Cancelar
          </Button>
        )}
        <Button type="submit" loading={loading}>
          {submitLabel}
        </Button>
      </div>
    </form>
  )
}
