import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import { Modal } from '@/components/molecules/Modal'

export interface HardDeleteTournamentDialogProps {
  open: boolean
  tournamentName: string
  loading?: boolean
  error?: string | null
  /** Receives the name exactly as typed; the server compares it again (400 when it differs). */
  onConfirm: (confirmName: string) => void
  onCancel: () => void
}

/**
 * Admin-only, irreversible deletion of a tournament in any status. The admin must type the tournament
 * name, so the button cannot be confirmed by a stray click.
 */
export function HardDeleteTournamentDialog({
  open,
  tournamentName,
  loading = false,
  error,
  onConfirm,
  onCancel,
}: HardDeleteTournamentDialogProps) {
  const [typed, setTyped] = useState('')
  const matches = typed.trim() === tournamentName
  const close = () => {
    setTyped('')
    onCancel()
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title="Eliminar torneo definitivamente"
      description="Se borrarán el torneo, sus canchas, inscripciones, partidos, resultados y alineaciones, junto con el reglamento y los comprobantes. Los equipos y jugadores se conservan. Esta acción no se puede deshacer."
      size="sm"
      footer={
        <>
          <Button variant="outline" onClick={close} disabled={loading}>
            Cancelar
          </Button>
          <Button variant="danger" onClick={() => onConfirm(typed.trim())} disabled={!matches} loading={loading}>
            Eliminar torneo
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <FormField label={`Escriba «${tournamentName}» para confirmar`} error={error}>
          <Input value={typed} onChange={(event) => setTyped(event.target.value)} autoComplete="off" spellCheck={false} />
        </FormField>
        {!error && typed.length > 0 && !matches && (
          <Alert kind="info">El texto debe coincidir exactamente con el nombre del torneo.</Alert>
        )}
      </div>
    </Modal>
  )
}
