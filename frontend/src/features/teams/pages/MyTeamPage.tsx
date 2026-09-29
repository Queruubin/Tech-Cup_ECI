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
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { JoinRequestResponse, TeamMember, TeamResponse } from '@/types/api'
import { teamsApi } from '../api'
import { EligibilityPanel } from '../components/EligibilityPanel'
import { JoinRequestsPanel } from '../components/JoinRequestsPanel'
import { TeamForm, type TeamFormValues } from '../components/TeamForm'
import { useEligibility, useMyTeam } from '../hooks/useTeams'

type PendingAction =
  | { kind: 'remove'; member: TeamMember }
  | { kind: 'inactivate' }
  | { kind: 'reject'; request: JoinRequestResponse }

export function MyTeamPage() {
  const { user, refreshMe } = useAuth()
  const teamQuery = useMyTeam()
  const team = teamQuery.data
  const teamId = team?.id ?? null
  const eligibilityQuery = useEligibility(teamId)
  const requestsQuery = useTeamJoinRequests(teamId)
  const [editing, setEditing] = useState(false)
  const [pending, setPending] = useState<PendingAction | null>(null)
  const [busyRequestId, setBusyRequestId] = useState<number | null>(null)

  const applyTeam = (updated: TeamResponse) => {
    teamQuery.setData(updated)
    eligibilityQuery.refetch()
  }

  const createTeam = useMutation(async (values: TeamFormValues) => {
    const created = await teamsApi.create(values)
    teamQuery.setData(created)
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
    const updated = await teamsApi.removeMember(team.id, member.userId)
    applyTeam(updated)
    return updated
  })

  const inactivateTeam = useMutation(async () => {
    if (!team) throw new Error('No hay equipo.')
    const updated = await teamsApi.inactivate(team.id)
    applyTeam(updated)
    await refreshMe().catch(() => null)
    return updated
  })

  const resolveRequest = useMutation(async (input: { request: JoinRequestResponse; accept: boolean }) => {
    const result = input.accept
      ? await playersApi.acceptJoinRequest(input.request.id)
      : await playersApi.rejectJoinRequest(input.request.id)
    requestsQuery.setData((previous) => previous?.filter((item) => item.id !== input.request.id) ?? null)
    if (input.accept) await teamQuery.refetch()
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

  const pendingBusy = removeMember.loading || inactivateTeam.loading || resolveRequest.loading

  return (
    <>
      <QueryState loading={teamQuery.loading} error={teamQuery.error} onRetry={teamQuery.refetch}>
        {!team ? (
          <>
            <PageHeader title="Crear equipo" description="Como capitán, usted será el primer integrante del equipo." />
            {!user?.hasProfile && (
              <Alert kind="warning" className="mb-4" title="Necesita un perfil deportivo">
                Para crear un equipo debe tener perfil deportivo, ya que el capitán también es jugador.{' '}
                <Link to="/profile" className="font-medium underline">
                  Crear mi perfil
                </Link>
              </Alert>
            )}
            <Card className="max-w-xl">
              <TeamForm
                loading={createTeam.loading}
                error={createTeam.error}
                fieldErrors={createTeam.fieldErrors}
                submitLabel="Crear equipo"
                onSubmit={(values) =>
                  createTeam
                    .mutate(values)
                    .then(() => toast.success('Equipo creado.'))
                    .catch(() => undefined)
                }
              />
            </Card>
          </>
        ) : (
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
                El equipo está inscrito en un torneo activo o en progreso. No es posible modificar el nombre, los
                colores ni retirar integrantes hasta que el torneo finalice.
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
                          <Button
                            size="sm"
                            variant="danger"
                            disabled={team.locked}
                            onClick={() => setPending({ kind: 'remove', member })}
                          >
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
                />
              </div>
              <div className="flex flex-col gap-6">
                <EligibilityPanel
                  eligibility={eligibilityQuery.data}
                  loading={eligibilityQuery.loading}
                  error={eligibilityQuery.error}
                  onRetry={eligibilityQuery.refetch}
                />
                {team.memberCount < 12 && (
                  <Card title="¿Faltan jugadores?">
                    <p className="text-sm text-stone-600">Consulte los jugadores disponibles por posición.</p>
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
        )}
      </QueryState>

      <ConfirmDialog
        open={pending !== null}
        title={
          pending?.kind === 'remove'
            ? 'Retirar integrante'
            : pending?.kind === 'inactivate'
              ? 'Inactivar equipo'
              : 'Rechazar solicitud'
        }
        description={
          pending?.kind === 'remove'
            ? `¿Desea retirar a ${pending.member.fullName} del equipo?`
            : pending?.kind === 'inactivate'
              ? 'El equipo dejará de estar disponible para inscripciones y solicitudes. Esta acción no se puede deshacer.'
              : pending?.kind === 'reject'
                ? `¿Desea rechazar la solicitud de ${pending.request.playerName}?`
                : undefined
        }
        confirmLabel={pending?.kind === 'inactivate' ? 'Inactivar' : pending?.kind === 'remove' ? 'Retirar' : 'Rechazar'}
        danger
        loading={pendingBusy}
        onConfirm={confirmPending}
        onCancel={() => setPending(null)}
      />
    </>
  )
}
