import { Modal } from '@/components/molecules/Modal'
import { BasicInfoForm } from '@/features/players/components/BasicInfoForm'
import type { UpdateUserRequest, UserResponse } from '@/types/api'

export interface EditUserModalProps {
  /** User being edited; the modal is closed while null. */
  user: UserResponse | null
  loading: boolean
  error: string | null
  fieldErrors: Record<string, string>
  onSubmit: (payload: UpdateUserRequest) => void
  onClose: () => void
}

/** ADMIN edit of another user's data: every editable field, the school relation included. */
export function EditUserModal({ user, loading, error, fieldErrors, onSubmit, onClose }: EditUserModalProps) {
  return (
    <Modal open={user !== null} onClose={onClose} title="Editar datos del usuario" description={user?.email ?? undefined}>
      {user && (
        <BasicInfoForm
          user={user}
          loading={loading}
          error={error}
          fieldErrors={fieldErrors}
          canEditRelation
          submitLabel="Guardar cambios"
          onSubmit={onSubmit}
          onCancel={onClose}
        />
      )}
    </Modal>
  )
}
