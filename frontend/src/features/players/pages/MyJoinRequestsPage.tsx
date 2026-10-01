import { useState } from 'react'
import { Link } from 'react-router'
import { Button } from '@/components/atoms/Button'
import { Card } from '@/components/molecules/Card'
import { ConfirmDialog } from '@/components/molecules/Modal'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { reloadOnError } from '@/lib/reloadOnError'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { JoinRequestResponse } from '@/types/api'
import { playersApi } from '../api'
import { JoinRequestList } from '../components/JoinRequestList'
import { useMyInvitations, useMyJoinRequests } from '../hooks/usePlayers'
import { splitInvitations } from '../invitations'

type PendingAction = { kind: 'cancelRequest'; request: JoinRequestResponse } | { kind: 'rejectInvitation'; invitation: JoinRequestResponse }

export function MyJoinRequestsPage() {
  const { refreshMe } = useAuth()
  const requestsQuery = useMyJoinRequests()
  const invitationsQuery = useMyInvitations()
  const [pending, setPending] = useState<PendingAction | null>(null)
  const [busyInvitationId, setBusyInvitationId] = useState<number | null>(null)

  const replaceIn = (list: JoinRequestResponse[] | null, updated: JoinRequestResponse) =>
    list?.map((item) => (item.id === updated.id ? updated : item)) ?? null

  // On failure each mutation reloads its list: the server may have resolved the row already.
  const cancelRequest = useMutation(async (id: number) => {
    const updated = await reloadOnError(() => playersApi.cancelJoinRequest(id), requestsQuery)
    requestsQuery.setData((previous) => replaceIn(previous, updated))
    return updated
  })

  const acceptInvitation = useMutation(async (invitation: JoinRequestResponse) => {
    const updated = await reloadOnError(() => playersApi.acceptInvitation(invitation.id), invitationsQuery, requestsQuery)
    // Accepting joins the team and cancels every other pending request / invitation server-side.
    await Promise.all([refreshMe().catch(() => null), invitationsQuery.refetch(), requestsQuery.refetch()])
    return updated
  })

  const rejectInvitation = useMutation(async (id: number) => {
    const updated = await reloadOnError(() => playersApi.rejectInvitation(id), invitationsQuery)
    invitationsQuery.setData((previous) => replaceIn(previous, updated))
    return updated
  })

  const handleAccept = (invitation: JoinRequestResponse) => {
    setBusyInvitationId(invitation.id)
    acceptInvitation
      .mutate(invitation)
      .then(() => toast.success(`Ahora forma parte del equipo ${invitation.teamName}`))
      .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'No fue posible aceptar la invitación.'))
      .finally(() => setBusyInvitationId(null))
  }

  const confirmPending = () => {
    if (!pending) return
    if (pending.kind === 'cancelRequest') {
      cancelRequest
        .mutate(pending.request.id)
        .then(() => toast.success('Solicitud cancelada.'))
        .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'No fue posible cancelar la solicitud.'))
        .finally(() => setPending(null))
    } else {
      const invitation = pending.invitation
      setBusyInvitationId(invitation.id)
      rejectInvitation
        .mutate(invitation.id)
        .then(() => toast.info('Invitación rechazada.'))
        .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'No fue posible rechazar la invitación.'))
        .finally(() => {
          setBusyInvitationId(null)
          setPending(null)
        })
    }
  }

  const invitations = splitInvitations(invitationsQuery.data)
  const sortedRequests = [...(requestsQuery.data ?? [])].sort((a, b) => b.createdAt.localeCompare(a.createdAt))

  return (
    <>
      <PageHeader
        title="Solicitudes e invitaciones"
        description="Invitaciones recibidas de los capitanes y solicitudes de vinculación que usted envió a equipos."
      />

      <div className="flex flex-col gap-6">
        <Card title="Invitaciones recibidas" description="Capitanes que lo invitan a unirse a su equipo." padded={false}>
          <div className="flex flex-col gap-4 p-4">
            <QueryState loading={invitationsQuery.loading} error={invitationsQuery.error} onRetry={invitationsQuery.refetch} inline>
              <JoinRequestList
                requests={invitations.pending}
                perspective="player"
                datePrefix="Recibida el"
                emptyTitle="Sin invitaciones pendientes"
                emptyDescription="Cuando un capitán lo invite a su equipo, la invitación aparecerá aquí."
                renderActions={(invitation) => (
                  <>
                    <Button
                      size="sm"
                      onClick={() => handleAccept(invitation)}
                      loading={busyInvitationId === invitation.id && acceptInvitation.loading}
                      disabled={busyInvitationId !== null}
                    >
                      Aceptar
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => setPending({ kind: 'rejectInvitation', invitation })}
                      disabled={busyInvitationId !== null}
                    >
                      Rechazar
                    </Button>
                  </>
                )}
              />
              {invitations.history.length > 0 && (
                <div>
                  <h3 className="mb-2 text-sm font-semibold text-stone-700">Historial de invitaciones</h3>
                  <JoinRequestList requests={invitations.history} perspective="player" datePrefix="Recibida el" />
                </div>
              )}
            </QueryState>
          </div>
        </Card>

        <Card
          title="Solicitudes enviadas"
          description="Solicitudes de vinculación enviadas a equipos. Solo puede tener una pendiente a la vez."
          padded={false}
        >
          <div className="p-4">
            <QueryState loading={requestsQuery.loading} error={requestsQuery.error} onRetry={requestsQuery.refetch} inline>
              <JoinRequestList
                requests={sortedRequests}
                perspective="player"
                emptyTitle="No ha enviado solicitudes"
                emptyDescription="Explore los equipos disponibles y solicite unirse al que prefiera."
                emptyAction={
                  <Link to="/teams">
                    <Button size="sm">Ver equipos</Button>
                  </Link>
                }
                renderActions={(request) =>
                  request.status === 'PENDING' ? (
                    <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'cancelRequest', request })}>
                      Cancelar
                    </Button>
                  ) : null
                }
              />
            </QueryState>
          </div>
        </Card>
      </div>

      <ConfirmDialog
        open={pending !== null}
        title={pending?.kind === 'rejectInvitation' ? 'Rechazar invitación' : 'Cancelar solicitud'}
        description={
          pending?.kind === 'rejectInvitation'
            ? `¿Desea rechazar la invitación de ${pending.invitation.teamName}?`
            : pending?.kind === 'cancelRequest'
              ? `¿Desea cancelar la solicitud enviada a ${pending.request.teamName}?`
              : undefined
        }
        confirmLabel={pending?.kind === 'rejectInvitation' ? 'Rechazar' : 'Sí, cancelar'}
        cancelLabel="Volver"
        danger
        loading={cancelRequest.loading || rejectInvitation.loading}
        onConfirm={confirmPending}
        onCancel={() => setPending(null)}
      />
    </>
  )
}
