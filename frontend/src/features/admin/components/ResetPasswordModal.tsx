import { useState, type FormEvent } from 'react'
import { Button } from '@/components/atoms/Button'
import { PasswordInput } from '@/components/atoms/PasswordInput'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import { Modal } from '@/components/molecules/Modal'
import { PASSWORD_RULE_HINT, validatePasswordChange, type PasswordChangeErrors } from '@/features/auth/validation'
import type { ResetPasswordRequest, UserResponse } from '@/types/api'

export interface ResetPasswordModalProps {
  /** Target user; the modal is closed when null. */
  user: UserResponse | null
  loading: boolean
  error: string | null
  fieldErrors: Record<string, string>
  onSubmit: (payload: ResetPasswordRequest) => void
  onClose: () => void
}

/** ADMIN action: sets a new password for another user (`POST /admin/users/{id}/password`). */
export function ResetPasswordModal({ user, loading, error, fieldErrors, onSubmit, onClose }: ResetPasswordModalProps) {
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [errors, setErrors] = useState<PasswordChangeErrors>({})
  const formId = 'reset-password-form'

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const next = validatePasswordChange({ currentPassword: '', newPassword, confirmPassword }, { requireCurrent: false })
    setErrors(next)
    if (Object.keys(next).length > 0) return
    onSubmit({ newPassword })
  }

  return (
    <Modal
      open={user !== null}
      onClose={onClose}
      title="Restablecer contraseña"
      description={user ? `${user.fullName} · ${user.email ?? '—'}` : undefined}
      size="sm"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={loading}>
            Cancelar
          </Button>
          <Button type="submit" form={formId} loading={loading}>
            Restablecer
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
        {error && <Alert kind="error">{error}</Alert>}
        <p className="text-sm text-stone-600">Comunique la nueva contraseña al usuario por un medio seguro.</p>
        <FormField label="Nueva contraseña" required hint={PASSWORD_RULE_HINT} error={errors.newPassword ?? fieldErrors.newPassword}>
          <PasswordInput
            autoComplete="new-password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
          />
        </FormField>
        <FormField label="Confirmar contraseña" required error={errors.confirmPassword}>
          <PasswordInput
            autoComplete="new-password"
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
          />
        </FormField>
      </form>
    </Modal>
  )
}
