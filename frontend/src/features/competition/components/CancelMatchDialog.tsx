import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { Select } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import { Modal } from '@/components/molecules/Modal'
import { cn } from '@/lib/cn'
import { CANCEL_REASON_LABELS, toOptions } from '@/lib/labels'
import { CANCEL_REASONS, type CancelReason, type MatchResponse } from '@/types/api'
import { isKnockoutPhase } from '../validation'

const REASON_OPTIONS = toOptions(CANCEL_REASONS, CANCEL_REASON_LABELS)

export interface CancelMatchInput {
  reason: CancelReason
  /** Required for knockout matches: the team that advances by walkover. */
  winnerTeamId?: number
}

export interface CancelMatchDialogProps {
  open: boolean
  match: Pick<MatchResponse, 'phase' | 'homeTeam' | 'awayTeam'>
  loading: boolean
  error: string | null
  onConfirm: (input: CancelMatchInput) => void
  onClose: () => void
}

/**
 * Asks for the cancellation reason required by `DELETE /matches/{id}?reason=`.
 * Knockout matches also require `winnerTeamId`: the chosen team advances to the next phase.
 */
export function CancelMatchDialog({ open, match, loading, error, onConfirm, onClose }: CancelMatchDialogProps) {
  const [reason, setReason] = useState<CancelReason | ''>('')
  const [winnerTeamId, setWinnerTeamId] = useState<number | null>(null)
  const [touched, setTouched] = useState(false)

  const requiresWinner = isKnockoutPhase(match.phase)
  const teams = [match.homeTeam, match.awayTeam]

  const handleConfirm = () => {
    setTouched(true)
    if (!reason) return
    if (requiresWinner && winnerTeamId === null) return
    onConfirm(requiresWinner && winnerTeamId !== null ? { reason, winnerTeamId } : { reason })
  }

  const winnerError = touched && requiresWinner && winnerTeamId === null ? 'Seleccione el equipo que avanza.' : undefined

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Cancelar partido"
      description="El partido conserva su registro con estado Cancelado."
      size="sm"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={loading}>
            Volver
          </Button>
          <Button variant="danger" onClick={handleConfirm} loading={loading}>
            Cancelar partido
          </Button>
        </>
      }
    >
      {error && (
        <Alert kind="error" className="mb-3">
          {error}
        </Alert>
      )}
      <FormField label="Motivo" required error={touched && !reason ? 'Seleccione el motivo de la cancelación.' : undefined}>
        <Select
          options={REASON_OPTIONS}
          placeholder="Seleccione un motivo"
          value={reason}
          onChange={(event) => setReason(event.target.value as CancelReason | '')}
        />
      </FormField>

      {requiresWinner && (
        <fieldset className="mt-4">
          <legend className="text-sm font-medium text-stone-800">
            Equipo que avanza
            <span className="ml-0.5 text-brand-600" aria-hidden="true">
              *
            </span>
          </legend>
          <p className="mt-1 text-xs text-stone-500">
            En fase eliminatoria el partido cancelado se resuelve por walkover: el equipo elegido avanza a la siguiente fase.
          </p>
          <div className="mt-2 flex flex-col gap-2">
            {teams.map((team) => (
              <label
                key={team.id}
                className={cn(
                  'flex cursor-pointer items-center gap-3 rounded-xl border px-3 py-2 text-sm',
                  winnerTeamId === team.id ? 'border-brand-400 bg-brand-50/60' : 'border-stone-200',
                )}
              >
                <input
                  type="radio"
                  name="walkover-winner"
                  value={team.id}
                  checked={winnerTeamId === team.id}
                  onChange={() => setWinnerTeamId(team.id)}
                  className="h-4 w-4 accent-brand-600"
                />
                <span className="font-medium text-ink">{team.name}</span>
              </label>
            ))}
          </div>
          {winnerError && (
            <p role="alert" className="mt-1.5 text-xs font-medium text-brand-600">
              {winnerError}
            </p>
          )}
        </fieldset>
      )}
    </Modal>
  )
}
