import { Link } from 'react-router'
import { Button } from '@/components/atoms/Button'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { QueryState } from '@/components/molecules/QueryState'
import { JoinRequestList } from '@/features/players/components/JoinRequestList'
import type { JoinRequestResponse } from '@/types/api'
import { ROSTER_FROZEN_NOTICE } from '../rosterLock'

export interface SentInvitationsPanelProps {
  invitations: JoinRequestResponse[] | null
  loading: boolean
  error: string | null
  onRetry?: () => void
  /** Id of the invitation currently being cancelled. */
  busyId: number | null
  onCancel: (invitation: JoinRequestResponse) => void
  /** The team is locked by a tournament: pending invitations can still be cancelled, no new ones sent. */
  rosterFrozen?: boolean
}

/** Captain view of the PENDING invitations sent by the team. */
export function SentInvitationsPanel({
  invitations,
  loading,
  error,
  onRetry,
  busyId,
  onCancel,
  rosterFrozen = false,
}: SentInvitationsPanelProps) {
  return (
    <Card title="Invitaciones enviadas" description="Jugadores invitados que aún no responden." padded={false}>
      <div className="p-4">
        {rosterFrozen && (
          <Alert kind="info" className="mb-4">
            {ROSTER_FROZEN_NOTICE}
          </Alert>
        )}
        <QueryState loading={loading} error={error} onRetry={onRetry} inline>
          <JoinRequestList
            requests={invitations ?? []}
            perspective="captain"
            emptyTitle="Sin invitaciones pendientes"
            emptyDescription={
              rosterFrozen ? 'No hay invitaciones pendientes.' : 'Invite jugadores disponibles desde la lista de jugadores.'
            }
            emptyAction={
              rosterFrozen ? undefined : (
                <Link to="/players">
                  <Button size="sm" variant="outline">
                    Ver jugadores disponibles
                  </Button>
                </Link>
              )
            }
            renderActions={(invitation) => (
              <Button
                size="sm"
                variant="outline"
                onClick={() => onCancel(invitation)}
                loading={busyId === invitation.id}
                disabled={busyId !== null}
              >
                Cancelar invitación
              </Button>
            )}
          />
        </QueryState>
      </div>
    </Card>
  )
}
