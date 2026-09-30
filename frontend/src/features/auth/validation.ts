import { todayIso } from '@/lib/format'
import type { AcademicProgram, DocumentType, InitialRole, RegisterRequest, SchoolRelation } from '@/types/api'

/** Semester range accepted by the backend for students. */
export const SEMESTER_MIN = 1
export const SEMESTER_MAX = 20

export function validateSemester(raw: string): string | undefined {
  const semester = Number(raw)
  if (!raw.trim() || !Number.isInteger(semester) || semester < SEMESTER_MIN || semester > SEMESTER_MAX) {
    return `Ingrese un semestre válido (${SEMESTER_MIN} a ${SEMESTER_MAX}).`
  }
  return undefined
}

/** Institutional email domains accepted for non-family members (mirrors `app.institutional-domains`). */
export const INSTITUTIONAL_DOMAINS = ['escuelaing.edu.co', 'mail.escuelaing.edu.co'] as const

export const PASSWORD_MIN_LENGTH = 8
export const PASSWORD_MAX_LENGTH = 72
export const PASSWORD_RULE_HINT = `Entre ${PASSWORD_MIN_LENGTH} y ${PASSWORD_MAX_LENGTH} caracteres, con al menos una letra y un número.`

/**
 * Password rule for `POST /auth/password` and `POST /admin/users/{id}/password`:
 * 8–72 characters, at least one letter and one digit.
 */
export function validateNewPassword(password: string): string | undefined {
  if (password.length < PASSWORD_MIN_LENGTH || password.length > PASSWORD_MAX_LENGTH) {
    return `La contraseña debe tener entre ${PASSWORD_MIN_LENGTH} y ${PASSWORD_MAX_LENGTH} caracteres.`
  }
  if (!/\p{L}/u.test(password) || !/\d/.test(password)) {
    return 'La contraseña debe incluir al menos una letra y un número.'
  }
  return undefined
}

export interface PasswordChangeValues {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

export type PasswordChangeErrors = Partial<Record<keyof PasswordChangeValues, string>>

/**
 * Validates a new password and its confirmation. `requireCurrent` is false for the admin reset,
 * which does not ask for the current password.
 */
export function validatePasswordChange(values: PasswordChangeValues, options: { requireCurrent?: boolean } = {}): PasswordChangeErrors {
  const { requireCurrent = true } = options
  const errors: PasswordChangeErrors = {}
  if (requireCurrent && !values.currentPassword) errors.currentPassword = 'Ingrese su contraseña actual.'
  const newPasswordError = validateNewPassword(values.newPassword)
  if (newPasswordError) errors.newPassword = newPasswordError
  if (values.confirmPassword !== values.newPassword) errors.confirmPassword = 'Las contraseñas no coinciden.'
  if (requireCurrent && values.currentPassword && !newPasswordError && values.newPassword === values.currentPassword) {
    errors.newPassword = 'La nueva contraseña debe ser diferente de la actual.'
  }
  return errors
}

export interface RegisterFormValues {
  fullName: string
  email: string
  password: string
  confirmPassword: string
  schoolRelation: SchoolRelation | ''
  academicProgram: AcademicProgram | ''
  semester: string
  birthDate: string
  documentType: DocumentType | ''
  documentNumber: string
  initialRole: InitialRole
}

export type RegisterFormErrors = Partial<Record<keyof RegisterFormValues, string>>

export const EMPTY_REGISTER_VALUES: RegisterFormValues = {
  fullName: '',
  email: '',
  password: '',
  confirmPassword: '',
  schoolRelation: '',
  academicProgram: '',
  semester: '',
  birthDate: '',
  documentType: '',
  documentNumber: '',
  initialRole: 'PLAYER',
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function emailDomain(email: string): string {
  const at = email.lastIndexOf('@')
  return at === -1 ? '' : email.slice(at + 1).toLowerCase()
}

export function isInstitutionalEmail(email: string): boolean {
  return (INSTITUTIONAL_DOMAINS as readonly string[]).includes(emailDomain(email))
}

export function requiresInstitutionalEmail(relation: SchoolRelation | ''): boolean {
  return relation !== '' && relation !== 'FAMILY'
}

export function validateEmail(email: string, relation: SchoolRelation | ''): string | undefined {
  const value = email.trim()
  if (!value) return 'El correo es obligatorio.'
  if (!EMAIL_PATTERN.test(value)) return 'Ingrese un correo válido.'
  if (relation === '') return undefined
  const institutional = isInstitutionalEmail(value)
  if (requiresInstitutionalEmail(relation) && !institutional) {
    return `Debe usar un correo institucional (${INSTITUTIONAL_DOMAINS.join(' o ')}).`
  }
  if (relation === 'FAMILY' && institutional) {
    return 'Los familiares deben registrarse con un correo personal, no institucional.'
  }
  return undefined
}

export function validateRegister(values: RegisterFormValues): RegisterFormErrors {
  const errors: RegisterFormErrors = {}

  if (values.fullName.trim().length < 3) errors.fullName = 'Ingrese su nombre completo.'

  const emailError = validateEmail(values.email, values.schoolRelation)
  if (emailError) errors.email = emailError

  if (values.password.length < PASSWORD_MIN_LENGTH) {
    errors.password = `La contraseña debe tener al menos ${PASSWORD_MIN_LENGTH} caracteres.`
  }
  if (values.confirmPassword !== values.password) errors.confirmPassword = 'Las contraseñas no coinciden.'

  if (!values.schoolRelation) errors.schoolRelation = 'Seleccione su relación con la Escuela.'
  if (!values.academicProgram) errors.academicProgram = 'Seleccione un programa académico.'

  if (values.schoolRelation === 'STUDENT') {
    const semesterError = validateSemester(values.semester)
    if (semesterError) errors.semester = semesterError
  }

  if (!values.birthDate) {
    errors.birthDate = 'Ingrese su fecha de nacimiento.'
  } else if (values.birthDate >= todayIso()) {
    // ISO `YYYY-MM-DD` strings compare lexicographically; today and future dates are rejected.
    errors.birthDate = 'La fecha de nacimiento debe ser anterior a hoy.'
  }

  if (!values.documentType) errors.documentType = 'Seleccione el tipo de documento.'
  if (!values.documentNumber.trim()) errors.documentNumber = 'Ingrese el número de documento.'

  return errors
}

/** Maps validated form values to the API payload. Call only after `validateRegister` returns no errors. */
export function toRegisterRequest(values: RegisterFormValues): RegisterRequest {
  return {
    fullName: values.fullName.trim(),
    email: values.email.trim().toLowerCase(),
    password: values.password,
    schoolRelation: values.schoolRelation as SchoolRelation,
    academicProgram: values.academicProgram as AcademicProgram,
    semester: values.schoolRelation === 'STUDENT' ? Number(values.semester) : null,
    birthDate: values.birthDate,
    documentType: values.documentType as DocumentType,
    documentNumber: values.documentNumber.trim(),
    initialRole: values.initialRole,
  }
}
