import { useState } from 'react'
import { Link } from 'react-router'
import { StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { ConfirmDialog } from '@/components/molecules/Modal'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { TeamRoster } from '@/components/organisms/TeamRoster'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { playersApi } from '@/features/players/api'
import { useTeamJoinRequests } from '@/features/players/hooks/usePlayers'
import { reloadOnError } from '@/lib/reloadOnError'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { JoinRequestResponse, TeamMember, TeamResponse } from '@/types/api'
import { teamsApi } from '../api'
import { EligibilityPanel } from '../components/EligibilityPanel'
import { JoinRequestsPanel } from '../components/JoinRequestsPanel'
import { SentInvitationsPanel } from '../components/SentInvitationsPanel'
import { TeamForm, type TeamFormValues } from '../components/TeamForm'
import { useEligibility, useMyTeam, useTeamInvitations } from '../hooks/useTeams'
import { selectMyTeamView } from '../myTeamView'

type PendingAction =
  | { kind: 'remove'; member: TeamMember }
  | { kind: 'inactivate' }
  | { kind: 'reject'; request: JoinRequestResponse }
  | { kind: 'cancelInvitation'; invitation: JoinRequestResponse }

const CONFIRM_TITLES: Record<PendingAction['kind'], string> = {
  remove: 'Retirar integrante',
  inactivate: 'Inactivar equipo',
  reject: 'Rechazar solicitud',
  cancelInvitation: 'Cancelar invitación',
}

const CONFIRM_LABELS: Record<PendingAction['kind'], string> = {
  remove: 'Retirar',
  inactivate: 'Inactivar',
  reject: 'Rechazar',
  cancelInvitation: 'Cancelar invitación',
}

function confirmDescription(pending: PendingAction | null): string | undefined {
  switch (pending?.kind) {
    case 'remove':
      return `¿Desea retirar a ${pending.member.fullName} del equipo?`
    case 'inactivate':
      return 'El equipo dejará de estar disponible para inscripciones y solicitudes, y usted dejará de ser su capitán. Esta acción no se puede deshacer.'
    case 'reject':
      return `¿Desea rechazar la solicitud de ${pending.request.playerName}?`
    case 'cancelInvitation':
      return `¿Desea cancelar la invitación enviada a ${pending.invitation.playerName}?`
    default:
      return undefined
  }
}

export function MyTeamPage() {
  const { user, refreshMe } = useAuth()
  const teamQuery = useMyTeam()
  const team = teamQuery.data
  const view = selectMyTeamView({ team, userId: user?.id ?? null, hasProfile: !!user?.hasProfile })
  // Captain-only data: members of someone else's team must not request it.
  const captainTeamId = view === 'captain' && team ? team.id : null
  const eligibilityQuery = useEligibility(captainTeamId)
  const requestsQuery = useTeamJoinRequests(captainTeamId)
  const invitationsQuery = useTeamInvitations(captainTeamId, 'PENDING')
  const [editing, setEditing] = useState(false)
  const [pending, setPending] = useState<PendingAction | null>(null)
  const [busyRequestId, setBusyRequestId] = useState<number | null>(null)
  const [busyInvitationId, setBusyInvitationId] = useState<number | null>(null)

  const applyTeam = (updated: TeamResponse) => {
    teamQuery.setData(updated)
    eligibilityQuery.refetch()
  }

  const createTeam = useMutation(async (values: TeamFormValues) => {
    const created = await teamsApi.create(values)
    teamQuery.setData(created)
    // The creator becomes CAPTAIN: reload the session so nav items and guards reflect it right away.
    await refreshMe().catch(() => null)
    return created
  })

  const updateTeam = useMutation(async (values: TeamFormValues) => {
    if (!team) throw new Error('No hay equipo.')
    const updated = await teamsApi.update(team.id, values)
    applyTeam(updated)
    return updated
  })

  const removeMember = useMutation(async (member: TeamMember) => {
    if (!team) throw new Error('No hay equipo.')
    // The member may already be gone (e.g. left the team): on failure show the server's roster.
    const updated = await reloadOnError(() => teamsApi.removeMember(team.id, member.userId), teamQuery)
    applyTeam(updated)
    return updated
  })

  const inactivateTeam = useMutation(async () => {
    if (!team) throw new Error('No hay equipo.')
    const updated = await teamsApi.inactivate(team.id)
    // `GET /teams/mine` only returns active teams, and the captain loses CAPTAIN.
    teamQuery.setData(null)
    setEditing(false)
    await refreshMe().catch(() => null)
    return updated
  })

  const resolveRequest = useMutation(async (input: { request: JoinRequestResponse; accept: boolean }) => {
    // The server may have resolved the request already (withdrawn, accepted elsewhere, player joined
    // another team): on failure reload so the stale row disappears. The caller still shows the toast.
    const result = await reloadOnError(
      () => (input.accept ? playersApi.acceptJoinRequest(input.request.id) : playersApi.rejectJoinRequest(input.request.id)),
      ...(input.accept ? [requestsQuery, teamQuery, invitationsQuery] : [requestsQuery]),
    )
    requestsQuery.setData((previous) => previous?.filter((item) => item.id !== input.request.id) ?? null)
    if (input.accept) {
      await teamQuery.refetch()
      // The new member's other pending invitations were cancelled server-side.
      invitationsQuery.refetch()
    }
    return result
  })

  const cancelInvitation = useMutation(async (invitation: JoinRequestResponse) => {
    // The invitation may have been accepted/rejected meanwhile: on failure reload so the stale row disappears.
    const result = await reloadOnError(() => teamsApi.cancelInvitation(invitation.id), invitationsQuery, teamQuery)
    invitationsQuery.setData((previous) => previous?.filter((item) => item.id !== invitation.id) ?? null)
    return result
  })

  const handleAccept = (request: JoinRequestResponse) => {
    setBusyRequestId(request.id)
    resolveRequest
      .mutate({ request, accept: true })
      .then(() => {
        toast.success(`${request.playerName} ahora es parte del equipo.`)
        eligibilityQuery.refetch()
      })
      .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'No fue posible aceptar la solicitud.'))
      .finally(() => setBusyRequestId(null))
  }

  const confirmPending = () => {
    if (!pending) return
    if (pending.kind === 'remove') {
      removeMember
        .mutate(pending.member)
        .then(() => toast.success(`${pending.member.fullName} fue retirado del equipo.`))
        .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'No fue posible retirar al integrante.'))
        .finally(() => setPending(null))
    } else if (pending.kind === 'inactivate') {
      inactivateTeam
        .mutate()
        .then(() => toast.success('El equipo fue inactivado.'))
        .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'No fue posible inactivar el equipo.'))
        .finally(() => setPending(null))
    } else if (pending.kind === 'cancelInvitation') {
      const invitation = pending.invitation
      setBusyInvitationId(invitation.id)
      cancelInvitation
        .mutate(invitation)
        .then(() => toast.info('Invitación cancelada.'))
        .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'No fue posible cancelar la invitación.'))
        .finally(() => {
          setBusyInvitationId(null)
          setPending(null)
        })
    } else {
      setBusyRequestId(pending.request.id)
      resolveRequest
        .mutate({ request: pending.request, accept: false })
        .then(() => toast.info('Solicitud rechazada.'))
        .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'No fue posible rechazar la solicitud.'))
        .finally(() => {
          setBusyRequestId(null)
          setPending(null)
        })
    }
  }

  const pendingBusy = removeMember.loading || inactivateTeam.loading || resolveRequest.loading || cancelInvitation.loading

  const renderContent = () => {
    if (view === 'needs-profile') {
      return (
        <>
          <PageHeader title="Mi equipo" description="Todavía no pertenece a un equipo." />
          <Alert kind="warning" className="max-w-xl" title="Necesita un perfil deportivo">
            Para crear un equipo o unirse a uno primero debe crear su perfil deportivo (posición y dorsal).{' '}
            <Link to="/profile" className="font-medium underline">
              Crear mi perfil
            </Link>
          </Alert>
        </>
      )
    }

    if (view === 'create') {
      return (
        <>
          <PageHeader
            title="Crear equipo"
            description={
              <>
                Todavía no pertenece a un equipo. Cree el suyo o{' '}
                <Link to="/teams" className="font-medium text-brand-700 hover:underline">
                  solicite unirse a uno existente
                </Link>
                .
              </>
            }
          />
          <Card className="max-w-xl">
            <p className="mb-4 text-sm text-stone-600">Al crear el equipo usted será su capitán.</p>
            <TeamForm
              loading={createTeam.loading}
              error={createTeam.error}
              fieldErrors={createTeam.fieldErrors}
              submitLabel="Crear equipo"
              onSubmit={(values) =>
                createTeam
                  .mutate(values)
                  .then(() => toast.success('Equipo creado. Ahora es el capitán.'))
                  .catch(() => undefined)
              }
            />
          </Card>
        </>
      )
    }

    if (!team) return null

    if (view === 'member') {
      return (
        <>
          <PageHeader
            title={team.name}
            description={
              <span className="flex flex-wrap items-center gap-2">
                <StatusBadge kind="team" value={team.status} />
                <span>Capitán: {team.captain.fullName}</span>
                <span>· Colores: {team.colors}</span>
                <span>· {team.memberCount} / 12 integrantes</span>
              </span>
            }
            actions={
              <Link to={`/teams/${team.id}`}>
                <Button size="sm" variant="outline">
                  Ver equipo
                </Button>
              </Link>
            }
          />
          <Alert kind="info" className="mb-4">
            Usted es integrante de este equipo. Solo el capitán puede modificarlo, gestionar solicitudes e invitar jugadores.
          </Alert>
          <Card title="Plantilla" padded={false} className="max-w-3xl">
            <div className="p-4">
              <TeamRoster members={team.members} captainId={team.captain.id} />
            </div>
          </Card>
        </>
      )
    }

    return (
      <>
        <PageHeader
          title={team.name}
          description={
            <span className="flex flex-wrap items-center gap-2">
              <StatusBadge kind="team" value={team.status} />
              <span>Colores: {team.colors}</span>
              <span>· {team.memberCount} / 12 integrantes</span>
            </span>
          }
          actions={
            <>
              <Link to={`/teams/${team.id}`}>
                <Button size="sm" variant="ghost">
                  Vista pública
                </Button>
              </Link>
              <Button size="sm" variant="outline" onClick={() => setEditing((value) => !value)} disabled={team.locked}>
                {editing ? 'Cerrar edición' : 'Editar equipo'}
              </Button>
              {team.status === 'ACTIVE' && (
                <Button size="sm" variant="danger" onClick={() => setPending({ kind: 'inactivate' })} disabled={team.locked}>
                  Inactivar
                </Button>
              )}
            </>
          }
        />

        {team.locked && (
          <Alert kind="info" className="mb-4">
            El equipo está inscrito en un torneo activo o en progreso. No es posible modificar el nombre ni los colores, ni
            sumar o retirar integrantes hasta que el torneo finalice.
          </Alert>
        )}

        {editing && !team.locked && (
          <Card title="Editar equipo" className="mb-6 max-w-xl">
            <TeamForm
              key={`${team.name}|${team.colors}`}
              initial={{ name: team.name, colors: team.colors }}
              loading={updateTeam.loading}
              error={updateTeam.error}
              fieldErrors={updateTeam.fieldErrors}
              submitLabel="Guardar cambios"
              onSubmit={(values) =>
                updateTeam
                  .mutate(values)
                  .then(() => {
                    toast.success('Equipo actualizado.')
                    setEditing(false)
                  })
                  .catch(() => undefined)
              }
              onCancel={() => setEditing(false)}
            />
          </Card>
        )}

        <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
          <div className="flex flex-col gap-6 lg:col-span-2">
            <Card title="Plantilla" padded={false}>
              <div className="p-4">
                <TeamRoster
                  members={team.members}
                  captainId={team.captain.id}
                  renderActions={(member) =>
                    member.userId !== team.captain.id ? (
                      <Button size="sm" variant="danger" disabled={team.locked} onClick={() => setPending({ kind: 'remove', member })}>
                        Retirar
                      </Button>
                    ) : null
                  }
                />
              </div>
            </Card>
            <JoinRequestsPanel
              requests={requestsQuery.data}
              loading={requestsQuery.loading}
              error={requestsQuery.error}
              onRetry={requestsQuery.refetch}
              busyId={busyRequestId}
              onAccept={handleAccept}
              onReject={(request) => setPending({ kind: 'reject', request })}
              rosterFrozen={team.locked}
            />
            <SentInvitationsPanel
              invitations={invitationsQuery.data}
              loading={invitationsQuery.loading}
              error={invitationsQuery.error}
              onRetry={invitationsQuery.refetch}
              busyId={busyInvitationId}
              onCancel={(invitation) => setPending({ kind: 'cancelInvitation', invitation })}
              rosterFrozen={team.locked}
            />
          </div>
          <div className="flex flex-col gap-6">
            <EligibilityPanel
              eligibility={eligibilityQuery.data}
              loading={eligibilityQuery.loading}
              error={eligibilityQuery.error}
              onRetry={eligibilityQuery.refetch}
            />
            {team.memberCount < 12 && team.status === 'ACTIVE' && !team.locked && (
              <Card title="¿Faltan jugadores?">
                <p className="text-sm text-stone-600">Consulte los jugadores disponibles por posición e invítelos a su equipo.</p>
                <Link to="/players" className="mt-3 inline-block">
                  <Button size="sm" variant="outline">
                    Ver jugadores disponibles
                  </Button>
                </Link>
              </Card>
            )}
          </div>
        </div>
      </>
    )
  }

  return (
    <>
      <QueryState loading={teamQuery.loading} error={teamQuery.error} onRetry={teamQuery.refetch}>
        {renderContent()}
      </QueryState>

      <ConfirmDialog
        open={pending !== null}
        title={pending ? CONFIRM_TITLES[pending.kind] : ''}
        description={confirmDescription(pending)}
        confirmLabel={pending ? CONFIRM_LABELS[pending.kind] : undefined}
        cancelLabel="Volver"
        danger
        loading={pendingBusy}
        onConfirm={confirmPending}
        onCancel={() => setPending(null)}
      />
    </>
  )
}
