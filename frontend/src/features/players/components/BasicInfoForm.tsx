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
  onSubmit: (payload: UpdateUserRequest) => void
}

const RELATION_OPTIONS = toOptions(SCHOOL_RELATIONS, SCHOOL_RELATION_LABELS)
const PROGRAM_OPTIONS = toOptions(ACADEMIC_PROGRAMS, ACADEMIC_PROGRAM_LABELS)

/** Edits the basic user information allowed by the contract (email and password are immutable). */
export function BasicInfoForm({ user, loading, error, fieldErrors, onSubmit }: BasicInfoFormProps) {
  const [fullName, setFullName] = useState(user.fullName)
  const [schoolRelation, setSchoolRelation] = useState<SchoolRelation>(user.schoolRelation)
  const [academicProgram, setAcademicProgram] = useState<AcademicProgram>(user.academicProgram)
  const [semester, setSemester] = useState(user.semester ? String(user.semester) : '')
  const [localErrors, setLocalErrors] = useState<{ fullName?: string; semester?: string }>({})

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const errors: { fullName?: string; semester?: string } = {}
    if (fullName.trim().length < 3) errors.fullName = 'Ingrese su nombre completo.'
    const semesterNumber = Number(semester)
    if (schoolRelation === 'STUDENT') {
      const semesterError = validateSemester(semester)
      if (semesterError) errors.semester = semesterError
    }
    setLocalErrors(errors)
    if (Object.keys(errors).length > 0) return
    onSubmit({
      fullName: fullName.trim(),
      schoolRelation,
      academicProgram,
      semester: schoolRelation === 'STUDENT' ? semesterNumber : null,
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
        <FormField label="Relación con la Escuela" required error={fieldErrors.schoolRelation}>
          <Select
            options={RELATION_OPTIONS}
            value={schoolRelation}
            onChange={(event) => setSchoolRelation(event.target.value as SchoolRelation)}
          />
        </FormField>
        <FormField label="Programa académico" required error={fieldErrors.academicProgram}>
          <Select
            options={PROGRAM_OPTIONS}
            value={academicProgram}
            onChange={(event) => setAcademicProgram(event.target.value as AcademicProgram)}
          />
        </FormField>
        {schoolRelation === 'STUDENT' && (
          <FormField label="Semestre" required error={localErrors.semester ?? fieldErrors.semester}>
            <Input type="number" min={SEMESTER_MIN} max={SEMESTER_MAX} value={semester} onChange={(event) => setSemester(event.target.value)} />
          </FormField>
        )}
      </div>
      <div>
        <Button type="submit" variant="outline" loading={loading}>
          Actualizar información
        </Button>
      </div>
    </form>
  )
}
