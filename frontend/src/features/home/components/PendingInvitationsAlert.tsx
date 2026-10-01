import { Link } from 'react-router'
import { Alert } from '@/components/molecules/Alert'

export interface PendingInvitationsAlertProps {
  count: number
}

function pendingInvitationsText(count: number): string {
  return count === 1 ? 'Tiene 1 invitación pendiente' : `Tiene ${count} invitaciones pendientes`
}

/** Home notice for a player with pending team invitations. Renders nothing when there are none. */
export function PendingInvitationsAlert({ count }: PendingInvitationsAlertProps) {
  if (count <= 0) return null
  return (
    <Alert kind="info" title={pendingInvitationsText(count)}>
      Un capitán lo invitó a unirse a su equipo.{' '}
      <Link to="/my-requests" className="font-medium underline">
        Ver invitaciones
      </Link>
    </Alert>
  )
}
