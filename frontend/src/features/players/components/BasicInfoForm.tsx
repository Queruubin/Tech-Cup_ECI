import { useState, type FormEvent } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { Select } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import { SEMESTER_MAX, SEMESTER_MIN, validateSemester } from '@/features/auth/validation'
import { ACADEMIC_PROGRAM_LABELS, SCHOOL_RELATION_LABELS, toOptions } from '@/lib/labels'
import {
  ACADEMIC_PROGRAMS,
  SCHOOL_RELATIONS,
  type AcademicProgram,
  type SchoolRelation,
  type UpdateUserRequest,
  type UserResponse,
} from '@/types/api'

export interface BasicInfoFormProps {
  user: UserResponse
  loading: boolean
  error: string | null
  fieldErrors: Record<string, string>
  /**
   * Only an ADMIN may change the school relation; for everyone else the select is disabled and the
   * current value is sent unchanged.
   */
  canEditRelation: boolean
  submitLabel?: string
  onSubmit: (payload: UpdateUserRequest) => void
  onCancel?: () => void
}

export const RELATION_LOCKED_HINT = 'Solo un administrador puede cambiarla.'

const RELATION_OPTIONS = toOptions(SCHOOL_RELATIONS, SCHOOL_RELATION_LABELS)
const PROGRAM_OPTIONS = toOptions(ACADEMIC_PROGRAMS, ACADEMIC_PROGRAM_LABELS)

/** Edits the basic user information allowed by the contract (email and password are immutable). */
export function BasicInfoForm({
  user,
  loading,
  error,
  fieldErrors,
  canEditRelation,
  submitLabel = 'Actualizar información',
  onSubmit,
  onCancel,
}: BasicInfoFormProps) {
  const [fullName, setFullName] = useState(user.fullName)
  const [schoolRelation, setSchoolRelation] = useState<SchoolRelation | null>(user.schoolRelation)
  const [academicProgram, setAcademicProgram] = useState<AcademicProgram | null>(user.academicProgram)
  const [semester, setSemester] = useState(user.semester ? String(user.semester) : '')
  const [localErrors, setLocalErrors] = useState<{ fullName?: string; semester?: string }>({})
  // A locked relation always reflects (and sends) the user's current value.
  const relation = canEditRelation ? schoolRelation : user.schoolRelation
  // Referees have no affiliation: unless an admin is editing, those fields do not apply to them.
  const showAffiliation = canEditRelation || user.schoolRelation !== null

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const errors: { fullName?: string; semester?: string } = {}
    if (fullName.trim().length < 3) errors.fullName = 'Ingrese su nombre completo.'
    const semesterNumber = Number(semester)
    if (relation === 'STUDENT') {
      const semesterError = validateSemester(semester)
      if (semesterError) errors.semester = semesterError
    }
    setLocalErrors(errors)
    if (Object.keys(errors).length > 0) return
    onSubmit({
      fullName: fullName.trim(),
      schoolRelation: relation,
      academicProgram,
      semester: relation === 'STUDENT' ? semesterNumber : null,
    })
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
      {error && <Alert kind="error">{error}</Alert>}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <FormField label="Nombre completo" required error={localErrors.fullName ?? fieldErrors.fullName} className="sm:col-span-2">
          <Input value={fullName} onChange={(event) => setFullName(event.target.value)} />
        </FormField>
        <FormField label="Correo electrónico" hint="El correo no se puede modificar.">
          <Input value={user.email ?? '—'} disabled readOnly />
        </FormField>
        {showAffiliation && (
          <FormField
            label="Relación con la Escuela"
            error={fieldErrors.schoolRelation}
            hint={canEditRelation ? undefined : RELATION_LOCKED_HINT}
          >
            <Select
              options={RELATION_OPTIONS}
              placeholder="Sin relación"
              value={relation ?? ''}
              disabled={!canEditRelation}
              onChange={(event) => setSchoolRelation((event.target.value || null) as SchoolRelation | null)}
            />
          </FormField>
        )}
        {showAffiliation && (
          <FormField label="Programa académico" error={fieldErrors.academicProgram}>
            <Select
              options={PROGRAM_OPTIONS}
              placeholder="Sin programa"
              value={academicProgram ?? ''}
              onChange={(event) => setAcademicProgram((event.target.value || null) as AcademicProgram | null)}
            />
          </FormField>
        )}
        {relation === 'STUDENT' && (
          <FormField label="Semestre" required error={localErrors.semester ?? fieldErrors.semester}>
            <Input type="number" min={SEMESTER_MIN} max={SEMESTER_MAX} value={semester} onChange={(event) => setSemester(event.target.value)} />
          </FormField>
        )}
      </div>
      <div className="flex gap-2">
        <Button type="submit" variant="outline" loading={loading}>
          {submitLabel}
        </Button>
        {onCancel && (
          <Button type="button" variant="ghost" onClick={onCancel} disabled={loading}>
            Cancelar
          </Button>
        )}
      </div>
    </form>
  )
}
