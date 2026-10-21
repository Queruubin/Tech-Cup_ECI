import { useId, useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { Card } from '@/components/molecules/Card'
import { ConfirmDialog } from '@/components/molecules/Modal'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { errorMessage } from '@/lib/api'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { TeamResponse } from '@/types/api'
import { teamsApi } from '../api'
import { LEAVE_FROZEN_HINT, leaveTeamQuestion } from '../rosterLock'

export interface LeaveTeamCardProps {
  team: Pick<TeamResponse, 'id' | 'name' | 'locked'>
  /** Called after the player left and the session was reloaded (the user no longer has a team). */
  onLeft: () => void
  className?: string
}

/**
 * "Salir del equipo" for a member who is not the captain (the caller decides who sees it). While the
 * team is locked by a tournament the button stays visible but disabled, with the reason next to it.
 */
export function LeaveTeamCard({ team, onLeft, className }: LeaveTeamCardProps) {
  const { refreshMe } = useAuth()
  const hintId = useId()
  const [confirming, setConfirming] = useState(false)

  const leave = useMutation(async () => {
    await teamsApi.leave(team.id)
    // The session carries `teamId`: reload it so every page shows the no-team state right away.
    await refreshMe().catch(() => null)
  })

  const confirm = () => {
    leave
      .mutate()
      .then(() => {
        toast.success(`Salió del equipo ${team.name}.`)
        setConfirming(false)
        onLeft()
      })
      .catch((cause: unknown) => {
        toast.error(errorMessage(cause, 'No fue posible salir del equipo.'))
        setConfirming(false)
      })
  }

  return (
    <>
      <Card title="Salir del equipo" className={className}>
        <p className="text-sm text-stone-600">
          Dejará de ser integrante del equipo. Para volver tendrá que pedir unirse de nuevo o recibir una invitación.
        </p>
        <div className="mt-3 flex flex-col items-start gap-1.5">
          <Button
            size="sm"
            variant="danger"
            disabled={team.locked}
            aria-describedby={team.locked ? hintId : undefined}
            onClick={() => setConfirming(true)}
          >
            Salir del equipo
          </Button>
          {team.locked && (
            <p id={hintId} className="text-xs text-stone-500">
              {LEAVE_FROZEN_HINT}
            </p>
          )}
        </div>
      </Card>

      <ConfirmDialog
        open={confirming}
        title="Salir del equipo"
        description={leaveTeamQuestion(team.name)}
        confirmLabel="Salir del equipo"
        cancelLabel="Volver"
        danger
        loading={leave.loading}
        onConfirm={confirm}
        onCancel={() => setConfirming(false)}
      />
    </>
  )
}
