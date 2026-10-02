import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { todayIso } from '@/lib/format'
import { ChangePasswordForm } from './components/ChangePasswordForm'
import {
  EMPTY_REGISTER_VALUES,
  PASSWORD_UPPERCASE_AND_DIGIT_ERROR,
  PLAYER_AGE_ERROR,
  ageInFullYears,
  validateEmail,
  validateNewPassword,
  validatePasswordChange,
  validatePlayerAge,
  toRegisterRequest,
  validateRegister,
  validateSemester,
} from './validation'

describe('validateRegister', () => {
  // Every self-registered account is a player; an adult within the 5-100 range is valid.
  const valid = {
    ...EMPTY_REGISTER_VALUES,
    fullName: 'Ana Pérez',
    email: 'ana.perez@escuelaing.edu.co',
    password: 'Clave1234',
    confirmPassword: 'Clave1234',
    academicProgram: 'SYSTEMS_ENGINEERING' as const,
    birthDate: '2003-04-10',
    documentType: 'CC' as const,
    documentNumber: '1001',
  }

  it('accepts semesters from 1 to 20 (backend range) and rejects the rest', () => {
    expect(validateSemester('1')).toBeUndefined()
    expect(validateSemester('20')).toBeUndefined()
    expect(validateSemester('0')).toMatch(/1 a 20/)
    expect(validateSemester('21')).toMatch(/1 a 20/)
    expect(validateSemester('2.5')).toMatch(/1 a 20/)
    expect(validateSemester('')).toMatch(/1 a 20/)
  })

  it('rejects a birth date of today or later', () => {
    const today = todayIso()
    const tomorrow = new Date()
    tomorrow.setDate(tomorrow.getDate() + 1)
    const tomorrowIso = `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, '0')}-${String(tomorrow.getDate()).padStart(2, '0')}`

    expect(validateRegister({ ...valid, birthDate: today }).birthDate).toMatch(/anterior a hoy/)
    expect(validateRegister({ ...valid, birthDate: tomorrowIso }).birthDate).toMatch(/anterior a hoy/)
    expect(validateRegister({ ...valid, birthDate: '2003-04-10' }).birthDate).toBeUndefined()
    expect(validateRegister(valid)).toEqual({})
  })

  it('accepts any syntactically valid e-mail', () => {
    expect(validateRegister({ ...valid, email: 'ana@gmail.com' }).email).toBeUndefined()
    expect(validateRegister({ ...valid, email: 'ana@escuelaing.edu.co' }).email).toBeUndefined()
    expect(validateEmail('')).toBe('El correo es obligatorio.')
    expect(validateEmail('ana@')).toBe('Ingrese un correo válido.')
    expect(validateEmail('ana perez@mail.com')).toBe('Ingrese un correo válido.')
  })

  it('requires an age of 5 to 100 full years, since every account is a player', () => {
    const child = { ...valid }
    const yearsAgo = (years: number, dayOffset = 0) => {
      const [y = 0, m = 1, d = 1] = todayIso().split('-').map(Number)
      const date = new Date(y - years, m - 1, d + dayOffset)
      return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    }

    expect(validateRegister({ ...child, birthDate: yearsAgo(5) }).birthDate).toBeUndefined()
    expect(validateRegister({ ...child, birthDate: yearsAgo(100) }).birthDate).toBeUndefined()
    expect(validateRegister({ ...child, birthDate: yearsAgo(101, 1) }).birthDate).toBeUndefined()
    expect(validateRegister({ ...child, birthDate: yearsAgo(5, 1) }).birthDate).toBe(PLAYER_AGE_ERROR)
    expect(validateRegister({ ...child, birthDate: yearsAgo(101) }).birthDate).toBe(PLAYER_AGE_ERROR)
    expect(validateRegister({ ...child, birthDate: '1900-04-10' }).birthDate).toBe(
      'Para ser jugador la edad debe estar entre 5 y 100 años.',
    )
  })

  it('applies the shared password rule and keeps the confirmation check', () => {
    const withPassword = (password: string) => validateRegister({ ...valid, password, confirmPassword: password })

    expect(withPassword('clave1234').password).toBe(PASSWORD_UPPERCASE_AND_DIGIT_ERROR)
    expect(withPassword('ClaveSegura').password).toBe(PASSWORD_UPPERCASE_AND_DIGIT_ERROR)
    expect(withPassword('Clave12').password).toMatch(/entre 8 y 72 caracteres/)
    expect(withPassword('Clave1234')).toEqual({})
    expect(validateRegister({ ...valid, confirmPassword: 'Clave12345' }).confirmPassword).toBe('Las contraseñas no coinciden.')
  })

  it('registers every account as a player without a school relation', () => {
    const request = toRegisterRequest(valid)
    expect(request.initialRole).toBe('PLAYER')
    expect(request.schoolRelation).toBeNull()
    expect(request.semester).toBeNull()
  })
})

describe('ageInFullYears', () => {
  it('counts full years from the string parts, the birthday included', () => {
    expect(ageInFullYears('2016-09-30', '2026-09-30')).toBe(10)
    expect(ageInFullYears('2016-10-01', '2026-09-30')).toBe(9)
    expect(ageInFullYears('2016-08-31', '2026-09-30')).toBe(10)
    expect(ageInFullYears('2016-02-29', '2026-02-28')).toBe(9)
    expect(ageInFullYears('not-a-date', '2026-09-30')).toBeNull()
  })

  it('validates the player range with an explicit today', () => {
    expect(validatePlayerAge('2021-09-30', '2026-09-30')).toBeUndefined()
    expect(validatePlayerAge('1926-09-30', '2026-09-30')).toBeUndefined()
    expect(validatePlayerAge('1925-10-01', '2026-09-30')).toBeUndefined()
    expect(validatePlayerAge('1925-09-30', '2026-09-30')).toBe(PLAYER_AGE_ERROR)
    expect(validatePlayerAge('2021-10-01', '2026-09-30')).toBe(PLAYER_AGE_ERROR)
  })
})

describe('validateNewPassword', () => {
  it('accepts 8 to 72 characters with at least one uppercase letter and one digit', () => {
    expect(validateNewPassword('Abcdefg1')).toBeUndefined()
    expect(validateNewPassword(`A${'a'.repeat(70)}1`)).toBeUndefined()
    expect(validateNewPassword('Clave-Segura-2026')).toBeUndefined()
    expect(validateNewPassword('ÁRBOL2026')).toBeUndefined()
  })

  it('rejects passwords outside the length range', () => {
    expect(validateNewPassword('Abc1234')).toMatch(/entre 8 y 72 caracteres/)
    expect(validateNewPassword(`A${'a'.repeat(71)}1`)).toMatch(/entre 8 y 72 caracteres/)
  })

  it('rejects passwords without an uppercase letter or without a digit', () => {
    expect(validateNewPassword('12345678')).toBe(PASSWORD_UPPERCASE_AND_DIGIT_ERROR)
    expect(validateNewPassword('clave1234')).toBe(PASSWORD_UPPERCASE_AND_DIGIT_ERROR)
    expect(validateNewPassword('ClaveSegura')).toBe(PASSWORD_UPPERCASE_AND_DIGIT_ERROR)
    expect(validateNewPassword('!!!!!!!!')).toBe(PASSWORD_UPPERCASE_AND_DIGIT_ERROR)
  })

  it('uses the same message as the backend', () => {
    expect(PASSWORD_UPPERCASE_AND_DIGIT_ERROR).toBe('La contraseña debe incluir al menos una letra mayúscula y un número.')
  })
})

describe('validatePasswordChange', () => {
  it('requires the current password, a valid new one and a matching confirmation', () => {
    const errors = validatePasswordChange({ currentPassword: '', newPassword: 'short1', confirmPassword: 'other' })
    expect(errors.currentPassword).toBeDefined()
    expect(errors.newPassword).toBeDefined()
    expect(errors.confirmPassword).toBe('Las contraseñas no coinciden.')
  })

  it('rejects reusing the current password', () => {
    const errors = validatePasswordChange({ currentPassword: 'MiClave123', newPassword: 'MiClave123', confirmPassword: 'MiClave123' })
    expect(errors.newPassword).toMatch(/diferente de la actual/)
  })

  it('returns no errors for a valid change and skips the current password for admin resets', () => {
    expect(validatePasswordChange({ currentPassword: 'Anterior1', newPassword: 'NuevaClave2', confirmPassword: 'NuevaClave2' })).toEqual({})
    expect(
      validatePasswordChange({ currentPassword: '', newPassword: 'NuevaClave2', confirmPassword: 'NuevaClave2' }, { requireCurrent: false }),
    ).toEqual({})
  })
})

describe('ChangePasswordForm', () => {
  function renderForm() {
    const onSubmit = vi.fn()
    render(<ChangePasswordForm loading={false} error={null} fieldErrors={{}} onSubmit={onSubmit} />)
    return { onSubmit }
  }

  it('blocks the submission when the new password breaks the rule or does not match', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderForm()

    await user.type(screen.getByLabelText(/^Contraseña actual/), 'Anterior1')
    await user.type(screen.getByLabelText(/^Nueva contraseña/), 'soloLetras')
    await user.type(screen.getByLabelText(/^Confirmar nueva contraseña/), 'otraCosa')
    await user.click(screen.getByRole('button', { name: 'Cambiar contraseña' }))

    expect(onSubmit).not.toHaveBeenCalled()
    const alerts = screen.getAllByRole('alert').map((node) => node.textContent)
    expect(alerts).toEqual(expect.arrayContaining([PASSWORD_UPPERCASE_AND_DIGIT_ERROR, 'Las contraseñas no coinciden.']))
  })

  it('submits only the current and new password once everything is valid', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderForm()

    await user.type(screen.getByLabelText(/^Contraseña actual/), 'Anterior1')
    await user.type(screen.getByLabelText(/^Nueva contraseña/), 'NuevaClave2')
    await user.type(screen.getByLabelText(/^Confirmar nueva contraseña/), 'NuevaClave2')
    await user.click(screen.getByRole('button', { name: 'Cambiar contraseña' }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit).toHaveBeenCalledWith({ currentPassword: 'Anterior1', newPassword: 'NuevaClave2' })
  })
})
