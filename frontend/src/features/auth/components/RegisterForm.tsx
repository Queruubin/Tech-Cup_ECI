import { useState, type FormEvent } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { Select } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import { ACADEMIC_PROGRAM_LABELS, DOCUMENT_TYPE_LABELS, toOptions } from '@/lib/labels'
import { ACADEMIC_PROGRAMS, DOCUMENT_TYPES, type AcademicProgram, type DocumentType } from '@/types/api'
import {
  EMPTY_REGISTER_VALUES,
  PASSWORD_MIN_LENGTH,
  PLAYER_AGE_HINT,
  validateRegister,
  type RegisterFormErrors,
  type RegisterFormValues,
} from '../validation'

export interface RegisterFormProps {
  loading: boolean
  error: string | null
  /** Field errors reported by the backend (`details[]`). */
  fieldErrors: Record<string, string>
  onSubmit: (values: RegisterFormValues) => void
}

const PROGRAM_OPTIONS = toOptions(ACADEMIC_PROGRAMS, ACADEMIC_PROGRAM_LABELS)
const DOCUMENT_OPTIONS = toOptions(DOCUMENT_TYPES, DOCUMENT_TYPE_LABELS)

export function RegisterForm({ loading, error, fieldErrors, onSubmit }: RegisterFormProps) {
  const [values, setValues] = useState<RegisterFormValues>(EMPTY_REGISTER_VALUES)
  const [errors, setErrors] = useState<RegisterFormErrors>({})
  const [submitted, setSubmitted] = useState(false)

  const update = <K extends keyof RegisterFormValues>(key: K, value: RegisterFormValues[K]) => {
    setValues((previous) => {
      const next = { ...previous, [key]: value }
      if (submitted) setErrors(validateRegister(next))
      return next
    })
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    const nextErrors = validateRegister(values)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) return
    onSubmit(values)
  }

  const errorFor = (key: keyof RegisterFormValues) => errors[key] ?? fieldErrors[key]

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-5" noValidate>
      {error && <Alert kind="error">{error}</Alert>}

      <fieldset className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <legend className="mb-2 text-sm font-semibold text-ink">Datos personales</legend>
        <FormField label="Nombre completo" required error={errorFor('fullName')} className="sm:col-span-2">
          <Input
            autoComplete="name"
            value={values.fullName}
            onChange={(event) => update('fullName', event.target.value)}
          />
        </FormField>
        <FormField
          label="Fecha de nacimiento"
          required
          error={errorFor('birthDate')}
          hint={PLAYER_AGE_HINT}
        >
          <Input
            type="date"
            autoComplete="bday"
            value={values.birthDate}
            onChange={(event) => update('birthDate', event.target.value)}
          />
        </FormField>
        <FormField label="Tipo de documento" required error={errorFor('documentType')}>
          <Select
            options={DOCUMENT_OPTIONS}
            placeholder="Seleccione"
            value={values.documentType}
            onChange={(event) => update('documentType', event.target.value as DocumentType | '')}
          />
        </FormField>
        <FormField label="Número de documento" required error={errorFor('documentNumber')} className="sm:col-span-2">
          <Input
            inputMode="numeric"
            value={values.documentNumber}
            onChange={(event) => update('documentNumber', event.target.value)}
          />
        </FormField>
      </fieldset>

      <fieldset className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <legend className="mb-2 text-sm font-semibold text-ink">Vínculo con la Escuela</legend>
        <FormField label="Programa académico" required error={errorFor('academicProgram')} className="sm:col-span-2">
          <Select
            options={PROGRAM_OPTIONS}
            placeholder="Seleccione"
            value={values.academicProgram}
            onChange={(event) => update('academicProgram', event.target.value as AcademicProgram | '')}
          />
        </FormField>
        <p className="text-xs text-stone-500 sm:col-span-2">
          Su cuenta se crea como jugador. Un administrador registrará después su relación con la Escuela.
        </p>
      </fieldset>

      <fieldset className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <legend className="mb-2 text-sm font-semibold text-ink">Cuenta</legend>
        <FormField label="Correo electrónico" required error={errorFor('email')} className="sm:col-span-2">
          <Input
            type="email"
            autoComplete="email"
            value={values.email}
            onChange={(event) => update('email', event.target.value)}
          />
        </FormField>
        <FormField
          label="Contraseña"
          required
          error={errorFor('password')}
          hint={`Mínimo ${PASSWORD_MIN_LENGTH} caracteres.`}
        >
          <Input
            type="password"
            autoComplete="new-password"
            value={values.password}
            onChange={(event) => update('password', event.target.value)}
          />
        </FormField>
        <FormField label="Confirmar contraseña" required error={errorFor('confirmPassword')}>
          <Input
            type="password"
            autoComplete="new-password"
            value={values.confirmPassword}
            onChange={(event) => update('confirmPassword', event.target.value)}
          />
        </FormField>
      </fieldset>

      <Button type="submit" size="lg" fullWidth loading={loading}>
        Crear cuenta
      </Button>
    </form>
  )
}
