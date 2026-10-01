import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { Textarea } from '@/components/atoms/Textarea'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import { Modal } from '@/components/molecules/Modal'
import type { PlayerProfileResponse } from '@/types/api'

export const INVITATION_MESSAGE_MAX = 500

export interface InvitePlayerModalProps {
  /** Player to invite; the modal is closed while null. */
  player: PlayerProfileResponse | null
  teamName: string
  loading: boolean
  error: string | null
  fieldErrors: Record<string, string>
  onSubmit: (message: string | null) => void
  onClose: () => void
}

/** Captain invitation with an optional message. Remount it (via `key`) per player to reset the message. */
export function InvitePlayerModal({ player, teamName, loading, error, fieldErrors, onSubmit, onClose }: InvitePlayerModalProps) {
  const [message, setMessage] = useState('')

  return (
    <Modal
      open={player !== null}
      onClose={onClose}
      title="Invitar jugador"
      description={player ? `Invitará a ${player.fullName} a unirse a ${teamName}.` : undefined}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={loading}>
            Cancelar
          </Button>
          <Button onClick={() => onSubmit(message.trim() || null)} loading={loading}>
            Enviar invitación
          </Button>
        </>
      }
    >
      {error && (
        <Alert kind="error" className="mb-3">
          {error}
        </Alert>
      )}
      <FormField
        label="Mensaje (opcional)"
        error={fieldErrors.message}
        hint={`${message.length} / ${INVITATION_MESSAGE_MAX} caracteres.`}
      >
        <Textarea value={message} maxLength={INVITATION_MESSAGE_MAX} onChange={(event) => setMessage(event.target.value)} />
      </FormField>
    </Modal>
  )
}
