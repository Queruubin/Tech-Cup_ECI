import { useState, type FormEvent } from 'react'
import { Button } from '@/components/atoms/Button'
import { PasswordInput } from '@/components/atoms/PasswordInput'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import type { ChangePasswordRequest } from '@/types/api'
import { PASSWORD_RULE_HINT, validatePasswordChange, type PasswordChangeErrors } from '../validation'

export interface ChangePasswordFormProps {
  loading: boolean
  error: string | null
  fieldErrors: Record<string, string>
  onSubmit: (payload: ChangePasswordRequest) => void
}

/** Self-service password change (current, new and confirmation). Validation mirrors the backend rule. */
export function ChangePasswordForm({ loading, error, fieldErrors, onSubmit }: ChangePasswordFormProps) {
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [errors, setErrors] = useState<PasswordChangeErrors>({})

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const next = validatePasswordChange({ currentPassword, newPassword, confirmPassword })
    setErrors(next)
    if (Object.keys(next).length > 0) return
    onSubmit({ currentPassword, newPassword })
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
      {error && <Alert kind="error">{error}</Alert>}
      <FormField label="Contraseña actual" required error={errors.currentPassword ?? fieldErrors.currentPassword}>
        <PasswordInput
          autoComplete="current-password"
          value={currentPassword}
          onChange={(event) => setCurrentPassword(event.target.value)}
        />
      </FormField>
      <FormField label="Nueva contraseña" required hint={PASSWORD_RULE_HINT} error={errors.newPassword ?? fieldErrors.newPassword}>
        <PasswordInput
          autoComplete="new-password"
          value={newPassword}
          onChange={(event) => setNewPassword(event.target.value)}
        />
      </FormField>
      <FormField label="Confirmar nueva contraseña" required error={errors.confirmPassword}>
        <PasswordInput
          autoComplete="new-password"
          value={confirmPassword}
          onChange={(event) => setConfirmPassword(event.target.value)}
        />
      </FormField>
      <div>
        <Button type="submit" variant="outline" loading={loading}>
          Cambiar contraseña
        </Button>
      </div>
    </form>
  )
}
