import { useState, type FormEvent } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { Select } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import {
  ACADEMIC_PROGRAM_LABELS,
  DOCUMENT_TYPE_LABELS,
  INITIAL_ROLE_LABELS,
  SCHOOL_RELATION_LABELS,
  toOptions,
} from '@/lib/labels'
import {
  ACADEMIC_PROGRAMS,
  DOCUMENT_TYPES,
  INITIAL_ROLES,
  SCHOOL_RELATIONS,
  type AcademicProgram,
  type DocumentType,
  type InitialRole,
  type SchoolRelation,
} from '@/types/api'
import {
  EMPTY_REGISTER_VALUES,
  INSTITUTIONAL_DOMAINS,
  PASSWORD_MIN_LENGTH,
  SEMESTER_MAX,
  requiresInstitutionalEmail,
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

const RELATION_OPTIONS = toOptions(SCHOOL_RELATIONS, SCHOOL_RELATION_LABELS)
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
  const isStudent = values.schoolRelation === 'STUDENT'
  const emailHint = requiresInstitutionalEmail(values.schoolRelation)
    ? `Use su correo institucional (${INSTITUTIONAL_DOMAINS.join(' o ')}).`
    : values.schoolRelation === 'FAMILY'
      ? 'Los familiares se registran con un correo personal.'
      : undefined

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
        <FormField label="Fecha de nacimiento" required error={errorFor('birthDate')}>
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
        <FormField label="Relación con la Escuela" required error={errorFor('schoolRelation')}>
          <Select
            options={RELATION_OPTIONS}
            placeholder="Seleccione"
            value={values.schoolRelation}
            onChange={(event) => update('schoolRelation', event.target.value as SchoolRelation | '')}
          />
        </FormField>
        <FormField label="Programa académico" required error={errorFor('academicProgram')}>
          <Select
            options={PROGRAM_OPTIONS}
            placeholder="Seleccione"
            value={values.academicProgram}
            onChange={(event) => update('academicProgram', event.target.value as AcademicProgram | '')}
          />
        </FormField>
        {isStudent && (
          <FormField label="Semestre" required error={errorFor('semester')}>
            <Input
              type="number"
              min={1}
              max={SEMESTER_MAX}
              inputMode="numeric"
              value={values.semester}
              onChange={(event) => update('semester', event.target.value)}
            />
          </FormField>
        )}
        <FormField
          label="Rol inicial"
          required
          error={errorFor('initialRole')}
          hint="Los jugadores pueden crear su perfil deportivo y unirse a equipos."
        >
          <Select
            options={toOptions(INITIAL_ROLES, INITIAL_ROLE_LABELS)}
            value={values.initialRole}
            onChange={(event) => update('initialRole', event.target.value as InitialRole)}
          />
        </FormField>
      </fieldset>

      <fieldset className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <legend className="mb-2 text-sm font-semibold text-ink">Cuenta</legend>
        <FormField label="Correo electrónico" required error={errorFor('email')} hint={emailHint} className="sm:col-span-2">
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
