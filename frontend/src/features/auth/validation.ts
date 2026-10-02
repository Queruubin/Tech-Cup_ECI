import { todayIso } from '@/lib/format'
import type { AcademicProgram, DocumentType, RegisterRequest } from '@/types/api'

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

export const PASSWORD_MIN_LENGTH = 8
export const PASSWORD_MAX_LENGTH = 72
export const PASSWORD_RULE_HINT = `Entre ${PASSWORD_MIN_LENGTH} y ${PASSWORD_MAX_LENGTH} caracteres, con al menos una letra mayúscula y un número.`
export const PASSWORD_UPPERCASE_AND_DIGIT_ERROR = 'La contraseña debe incluir al menos una letra mayúscula y un número.'

/**
 * Password rule shared by every request that sets a password (register, referee creation, self-service
 * change and admin reset): 8–72 characters, at least one uppercase letter and one digit. Mirrors the backend.
 */
export function validateNewPassword(password: string): string | undefined {
  if (password.length < PASSWORD_MIN_LENGTH || password.length > PASSWORD_MAX_LENGTH) {
    return `La contraseña debe tener entre ${PASSWORD_MIN_LENGTH} y ${PASSWORD_MAX_LENGTH} caracteres.`
  }
  if (!/\p{Lu}/u.test(password) || !/\d/.test(password)) {
    return PASSWORD_UPPERCASE_AND_DIGIT_ERROR
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
  academicProgram: AcademicProgram | ''
  birthDate: string
  documentType: DocumentType | ''
  documentNumber: string
}

export type RegisterFormErrors = Partial<Record<keyof RegisterFormValues, string>>

export const EMPTY_REGISTER_VALUES: RegisterFormValues = {
  fullName: '',
  email: '',
  password: '',
  confirmPassword: '',
  academicProgram: '',
  birthDate: '',
  documentType: '',
  documentNumber: '',
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function validateEmail(email: string): string | undefined {
  const value = email.trim()
  if (!value) return 'El correo es obligatorio.'
  if (!EMAIL_PATTERN.test(value)) return 'Ingrese un correo válido.'
  return undefined
}

/** Age range (inclusive, full years) required to register with the PLAYER role. Mirrors the backend rule. */
export const PLAYER_MIN_AGE = 5
export const PLAYER_MAX_AGE = 100
export const PLAYER_AGE_ERROR = `Para ser jugador la edad debe estar entre ${PLAYER_MIN_AGE} y ${PLAYER_MAX_AGE} años.`
export const PLAYER_AGE_HINT = `Los jugadores deben tener entre ${PLAYER_MIN_AGE} y ${PLAYER_MAX_AGE} años cumplidos.`

const ISO_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/

/**
 * Full years elapsed between two `YYYY-MM-DD` dates, computed from the string parts so no time zone
 * shift can move the day (`new Date('YYYY-MM-DD')` is parsed as UTC). Returns null for malformed input.
 */
function isoDateParts(value: string): [number, number, number] | null {
  const match = ISO_DATE_PATTERN.exec(value)
  return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null
}

export function ageInFullYears(birthDate: string, today: string = todayIso()): number | null {
  const birth = isoDateParts(birthDate)
  const now = isoDateParts(today)
  if (!birth || !now) return null
  const [by, bm, bd] = birth
  const [ty, tm, td] = now
  const birthdayPending = tm < bm || (tm === bm && td < bd)
  return ty - by - (birthdayPending ? 1 : 0)
}

/** PLAYER registrations require an age between {@link PLAYER_MIN_AGE} and {@link PLAYER_MAX_AGE}. */
export function validatePlayerAge(birthDate: string, today: string = todayIso()): string | undefined {
  const age = ageInFullYears(birthDate, today)
  if (age === null || age < PLAYER_MIN_AGE || age > PLAYER_MAX_AGE) return PLAYER_AGE_ERROR
  return undefined
}

export function validateRegister(values: RegisterFormValues): RegisterFormErrors {
  const errors: RegisterFormErrors = {}

  if (values.fullName.trim().length < 3) errors.fullName = 'Ingrese su nombre completo.'

  const emailError = validateEmail(values.email)
  if (emailError) errors.email = emailError

  const passwordError = validateNewPassword(values.password)
  if (passwordError) errors.password = passwordError
  if (values.confirmPassword !== values.password) errors.confirmPassword = 'Las contraseñas no coinciden.'

  if (!values.academicProgram) errors.academicProgram = 'Seleccione un programa académico.'

  if (!values.birthDate) {
    errors.birthDate = 'Ingrese su fecha de nacimiento.'
  } else if (values.birthDate >= todayIso()) {
    // ISO `YYYY-MM-DD` strings compare lexicographically; today and future dates are rejected.
    errors.birthDate = 'La fecha de nacimiento debe ser anterior a hoy.'
  } else {
    // Every self-registered account is a player, so the player age range always applies.
    const ageError = validatePlayerAge(values.birthDate)
    if (ageError) errors.birthDate = ageError
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
    // The school relation (and therefore the semester) is assigned later by an administrator.
    schoolRelation: null,
    academicProgram: values.academicProgram as AcademicProgram,
    semester: null,
    birthDate: values.birthDate,
    documentType: values.documentType as DocumentType,
    documentNumber: values.documentNumber.trim(),
    initialRole: 'PLAYER',
  }
}
