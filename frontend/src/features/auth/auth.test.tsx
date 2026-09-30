import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { todayIso } from '@/lib/format'
import { ChangePasswordForm } from './components/ChangePasswordForm'
import { EMPTY_REGISTER_VALUES, validateNewPassword, validatePasswordChange, validateRegister, validateSemester } from './validation'

describe('validateRegister', () => {
  const valid = {
    ...EMPTY_REGISTER_VALUES,
    fullName: 'Ana Pérez',
    email: 'ana.perez@escuelaing.edu.co',
    password: 'Clave1234',
    confirmPassword: 'Clave1234',
    schoolRelation: 'STUDENT' as const,
    academicProgram: 'SYSTEMS_ENGINEERING' as const,
    semester: '5',
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
    expect(validateRegister({ ...valid, semester: '15' }).semester).toBeUndefined()
    expect(validateRegister({ ...valid, semester: '21' }).semester).toMatch(/1 a 20/)
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
})

describe('validateNewPassword', () => {
  it('accepts 8 to 72 characters with at least one letter and one digit', () => {
    expect(validateNewPassword('abcdefg1')).toBeUndefined()
    expect(validateNewPassword(`${'a'.repeat(71)}1`)).toBeUndefined()
    expect(validateNewPassword('Clave-Segura-2026')).toBeUndefined()
  })

  it('rejects passwords outside the length range', () => {
    expect(validateNewPassword('abc1234')).toMatch(/entre 8 y 72 caracteres/)
    expect(validateNewPassword(`${'a'.repeat(72)}1`)).toMatch(/entre 8 y 72 caracteres/)
  })

  it('rejects passwords without a letter or without a digit', () => {
    expect(validateNewPassword('12345678')).toMatch(/una letra y un número/)
    expect(validateNewPassword('abcdefgh')).toMatch(/una letra y un número/)
    expect(validateNewPassword('!!!!!!!!')).toMatch(/una letra y un número/)
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
    expect(alerts).toEqual(expect.arrayContaining([expect.stringMatching(/una letra y un número/), 'Las contraseñas no coinciden.']))
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
