import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { Badge, StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { Textarea } from '@/components/atoms/Textarea'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { EmptyState } from '@/components/molecules/EmptyState'
import { FormField } from '@/components/molecules/FormField'
import { ConfirmDialog, Modal } from '@/components/molecules/Modal'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { TeamRoster } from '@/components/organisms/TeamRoster'
import { adminApi } from '@/features/admin/api'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { playersApi } from '@/features/players/api'
import { errorMessage } from '@/lib/api'
import { formatPhone } from '@/lib/phone'
import { useMutation, useQuery } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import { teamsApi } from '../api'
import { EligibilityPanel } from '../components/EligibilityPanel'
import { LeaveTeamCard } from '../components/LeaveTeamCard'
import { TeamPhoto } from '../components/TeamPhoto'
import { TeamPhotoModal } from '../components/TeamPhotoModal'
import { useEligibility, useTeam } from '../hooks/useTeams'
import { joinRequestAvailability, TEAM_NOT_RECRUITING_HINT } from '../rosterLock'
import { canManageTeamPhoto } from '../teamPhoto'
import { MAX_TEAM_MEMBERS } from '../teamSize'
import { REMOVE_PHOTO_QUESTION } from '@/features/admin/photoModeration'
import type { TeamMember } from '@/types/api'

export function TeamDetailPage() {
  const params = useParams<{ id: string }>()
  const teamId = params.id && /^\d+$/.test(params.id) ? Number(params.id) : null
  const { user, hasRole, hasExactRole } = useAuth()
  const navigate = useNavigate()
  const isAdmin = hasRole('ADMIN')
  // Staff open each member's full profile; players keep the plain roster.
  const isStaff = hasRole('ADMIN', 'ORGANIZER')
  const teamQuery = useTeam(teamId)
  const eligibilityQuery = useEligibility(teamId)
  // The captain's phone is personal data: only staff read it, through the staff user view
  // (the team response never carries it).
  const captainId = teamQuery.data?.captain.id ?? null
  const captainQuery = useQuery((signal) => adminApi.getUser(captainId as number, signal), [captainId], {
    enabled: isStaff && captainId !== null,
  })
  const captainPhone = isStaff ? captainQuery.data?.phoneNumber ?? null : null
  const [joinOpen, setJoinOpen] = useState(false)
  const [message, setMessage] = useState('')
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [photoMember, setPhotoMember] = useState<TeamMember | null>(null)
  const [teamPhotoOpen, setTeamPhotoOpen] = useState(false)

  const joinRequest = useMutation((payload: { teamId: number; message?: string }) =>
    playersApi.createJoinRequest(payload.teamId, payload.message ? { message: payload.message } : {}),
  )
  const deleteTeam = useMutation((id: number) => teamsApi.remove(id))
  const removePhoto = useMutation(async (member: TeamMember) => {
    await adminApi.removeUserPhoto(member.userId)
    teamQuery.setData((previous) =>
      previous
        ? {
            ...previous,
            members: previous.members.map((item) => (item.userId === member.userId ? { ...item, photoFileId: null } : item)),
          }
        : previous,
    )
  })

  if (teamId === null) {
    return <EmptyState title="Equipo no encontrado" description="El identificador del equipo no es válido." />
  }

  const team = teamQuery.data
  const isMember = !!team && team.members.some((member) => member.userId === user?.id)
  const isCaptainOfTeam = !!team && team.captain.id === user?.id
  // Captain, organizer or admin; never blocked by a tournament (the photo decides nothing).
  const canEditTeamPhoto = canManageTeamPhoto(user, team ?? null)
  const joinAvailability = joinRequestAvailability(team, {
    userId: user?.id ?? null,
    // Asking to join is personal (like founding a team): ADMIN implies PLAYER for permissions, but
    // only a user who actually holds PLAYER may join; the server refuses the others with 403.
    isPlayer: hasExactRole('PLAYER'),
    teamId: user?.teamId ?? null,
  })
  const canRequestJoin = joinAvailability === 'open'

  const submitJoin = () => {
    joinRequest
      .mutate({ teamId, message: message.trim() || undefined })
      .then(() => {
        toast.success('Solicitud enviada. El capitán la revisará.')
        setJoinOpen(false)
        setMessage('')
      })
      .catch(() => undefined)
  }

  const confirmRemovePhoto = () => {
    if (!photoMember) return
    const target = photoMember
    removePhoto
      .mutate(target)
      .then(() => toast.success(`Se quitó la foto de ${target.fullName}.`))
      .catch((cause: unknown) => toast.error(errorMessage(cause, 'No fue posible quitar la foto.')))
      .finally(() => setPhotoMember(null))
  }

  const confirmDelete = () => {
    deleteTeam
      .mutate(teamId)
      .then(() => {
        toast.success('Equipo eliminado.')
        navigate('/teams', { replace: true })
      })
      .catch((cause: unknown) => toast.error(errorMessage(cause, 'No fue posible eliminar el equipo.')))
      .finally(() => setDeleteOpen(false))
  }

  return (
    <>
      <QueryState loading={teamQuery.loading} error={teamQuery.error} onRetry={teamQuery.refetch}>
        {team && (
          <>
            <PageHeader
              title={team.name}
              leading={<TeamPhoto name={team.name} photoFileId={team.photoFileId} size="lg" />}
              description={
                <span className="flex flex-wrap items-center gap-2">
                  <StatusBadge kind="team" value={team.status} />
                  {isMember && <Badge tone="accent">Mi equipo</Badge>}
                  <span>Capitán: {team.captain.fullName}</span>
                  {isStaff && captainQuery.data && (
                    <span>
                      · Celular del capitán:{' '}
                      {captainPhone ? (
                        <a href={`tel:${captainPhone}`} className="font-medium text-brand-700 underline-offset-2 hover:underline">
                          {formatPhone(captainPhone)}
                        </a>
                      ) : (
                        'sin registrar'
                      )}
                    </span>
                  )}
                  <span>· Colores: {team.colors}</span>
                </span>
              }
              actions={
                <>
                  {canEditTeamPhoto && (
                    <Button size="sm" variant="ghost" onClick={() => setTeamPhotoOpen(true)}>
                      {team.photoFileId ? 'Cambiar foto' : 'Agregar foto'}
                    </Button>
                  )}
                  {isCaptainOfTeam && (
                    <Link to="/my-team">
                      <Button size="sm" variant="outline">
                        Gestionar equipo
                      </Button>
                    </Link>
                  )}
                  {joinAvailability !== 'hidden' && (
                    <Button
                      size="sm"
                      onClick={() => setJoinOpen(true)}
                      disabled={!canRequestJoin || !user?.hasProfile}
                      title={canRequestJoin ? undefined : TEAM_NOT_RECRUITING_HINT}
                    >
                      Solicitar unirme
                    </Button>
                  )}
                  {isAdmin && (
                    <Button size="sm" variant="danger" onClick={() => setDeleteOpen(true)}>
                      Eliminar equipo
                    </Button>
                  )}
                </>
              }
            />

            {joinAvailability === 'frozen' && (
              <Alert kind="info" className="mb-4">
                {TEAM_NOT_RECRUITING_HINT}
              </Alert>
            )}

            {canRequestJoin && !user?.hasProfile && (
              <Alert kind="info" className="mb-4">
                Para solicitar unirse a un equipo primero debe{' '}
                <Link to="/profile" className="font-medium underline">
                  crear su perfil deportivo
                </Link>
                .
              </Alert>
            )}

            <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
              <div className="lg:col-span-2">
                <Card
                  title="Plantilla"
                  description={`${team.memberCount} de ${MAX_TEAM_MEMBERS} integrantes`}
                  padded={false}
                >
                  <div className="p-4">
                    <TeamRoster
                      members={team.members}
                      captainId={team.captain.id}
                      memberHref={isStaff ? (member) => `/users/${member.userId}` : undefined}
                      renderActions={
                        isAdmin
                          ? (member) =>
                              member.photoFileId ? (
                                <Button size="sm" variant="ghost" onClick={() => setPhotoMember(member)}>
                                  Quitar foto
                                </Button>
                              ) : null
                          : undefined
                      }
                    />
                  </div>
                </Card>
              </div>
              <div className="flex flex-col gap-6">
                <EligibilityPanel
                  eligibility={eligibilityQuery.data}
                  loading={eligibilityQuery.loading}
                  error={eligibilityQuery.error}
                  onRetry={eligibilityQuery.refetch}
                />
                {isMember && !isCaptainOfTeam && team.status === 'ACTIVE' && (
                  <LeaveTeamCard
                    team={team}
                    onLeft={() => {
                      teamQuery.refetch()
                      eligibilityQuery.refetch()
                    }}
                  />
                )}
              </div>
            </div>
          </>
        )}
      </QueryState>

      {canEditTeamPhoto && (
        <TeamPhotoModal
          team={teamPhotoOpen ? team ?? null : null}
          onChanged={(updated) => teamQuery.setData(updated)}
          onClose={() => setTeamPhotoOpen(false)}
        />
      )}

      {isAdmin && (
        <ConfirmDialog
          open={photoMember !== null}
          title={photoMember ? `Quitar la foto de ${photoMember.fullName}` : 'Quitar foto'}
          description={REMOVE_PHOTO_QUESTION}
          confirmLabel="Quitar foto"
          danger
          loading={removePhoto.loading}
          onConfirm={confirmRemovePhoto}
          onCancel={() => setPhotoMember(null)}
        />
      )}

      {isAdmin && (
        <ConfirmDialog
          open={deleteOpen}
          title="Eliminar equipo"
          description={
            team
              ? `Se eliminará el equipo ${team.name} con sus integrantes, solicitudes e inscripciones rechazadas o canceladas. Los jugadores conservan sus cuentas. No se puede eliminar un equipo con partidos o con una inscripción en revisión o aprobada. Esta acción no se puede deshacer.`
              : undefined
          }
          confirmLabel="Eliminar equipo"
          danger
          loading={deleteTeam.loading}
          onConfirm={confirmDelete}
          onCancel={() => setDeleteOpen(false)}
        />
      )}

      <Modal
        open={joinOpen}
        onClose={() => setJoinOpen(false)}
        title="Solicitar unirme al equipo"
        description={team ? `Enviará una solicitud al capitán de ${team.name}.` : undefined}
        footer={
          <>
            <Button variant="outline" onClick={() => setJoinOpen(false)} disabled={joinRequest.loading}>
              Cancelar
            </Button>
            <Button onClick={submitJoin} loading={joinRequest.loading}>
              Enviar solicitud
            </Button>
          </>
        }
      >
        {joinRequest.error && (
          <Alert kind="error" className="mb-3">
            {joinRequest.error}
          </Alert>
        )}
        <FormField label="Mensaje (opcional)" error={joinRequest.fieldErrors.message} hint="Preséntese brevemente al capitán.">
          <Textarea value={message} maxLength={500} onChange={(event) => setMessage(event.target.value)} />
        </FormField>
      </Modal>
    </>
  )
}
