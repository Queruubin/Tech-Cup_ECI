import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Badge, StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { Textarea } from '@/components/atoms/Textarea'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { EmptyState } from '@/components/molecules/EmptyState'
import { FormField } from '@/components/molecules/FormField'
import { Modal } from '@/components/molecules/Modal'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { TeamRoster } from '@/components/organisms/TeamRoster'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { playersApi } from '@/features/players/api'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import { EligibilityPanel } from '../components/EligibilityPanel'
import { useEligibility, useTeam } from '../hooks/useTeams'

export function TeamDetailPage() {
  const params = useParams<{ id: string }>()
  const teamId = params.id && /^\d+$/.test(params.id) ? Number(params.id) : null
  const { user, hasRole } = useAuth()
  const teamQuery = useTeam(teamId)
  const eligibilityQuery = useEligibility(teamId)
  const [joinOpen, setJoinOpen] = useState(false)
  const [message, setMessage] = useState('')

  const joinRequest = useMutation((payload: { teamId: number; message?: string }) =>
    playersApi.createJoinRequest(payload.teamId, payload.message ? { message: payload.message } : {}),
  )

  if (teamId === null) {
    return <EmptyState title="Equipo no encontrado" description="El identificador del equipo no es válido." />
  }

  const team = teamQuery.data
  const isMember = !!team && team.members.some((member) => member.userId === user?.id)
  const isCaptainOfTeam = !!team && team.captain.id === user?.id
  const canRequestJoin =
    hasRole('PLAYER') && !isMember && !user?.teamId && !!team && team.status === 'ACTIVE' && team.memberCount < 12

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

  return (
    <>
      <QueryState loading={teamQuery.loading} error={teamQuery.error} onRetry={teamQuery.refetch}>
        {team && (
          <>
            <PageHeader
              title={team.name}
              description={
                <span className="flex flex-wrap items-center gap-2">
                  <StatusBadge kind="team" value={team.status} />
                  {isMember && <Badge tone="accent">Mi equipo</Badge>}
                  <span>Capitán: {team.captain.fullName}</span>
                  <span>· Colores: {team.colors}</span>
                </span>
              }
              actions={
                <>
                  {isCaptainOfTeam && (
                    <Link to="/my-team">
                      <Button size="sm" variant="outline">
                        Gestionar equipo
                      </Button>
                    </Link>
                  )}
                  {canRequestJoin && (
                    <Button size="sm" onClick={() => setJoinOpen(true)} disabled={!user?.hasProfile}>
                      Solicitar unirme
                    </Button>
                  )}
                </>
              }
            />

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
                  description={`${team.memberCount} de 12 integrantes`}
                  padded={false}
                >
                  <div className="p-4">
                    <TeamRoster members={team.members} captainId={team.captain.id} />
                  </div>
                </Card>
              </div>
              <div>
                <EligibilityPanel
                  eligibility={eligibilityQuery.data}
                  loading={eligibilityQuery.loading}
                  error={eligibilityQuery.error}
                  onRetry={eligibilityQuery.refetch}
                />
              </div>
            </div>
          </>
        )}
      </QueryState>

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
