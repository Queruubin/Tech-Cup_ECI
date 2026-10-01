import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { Select } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { EmptyState } from '@/components/molecules/EmptyState'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { teamsApi } from '@/features/teams/api'
import { useTeam, useTeamInvitations } from '@/features/teams/hooks/useTeams'
import { isRosterFrozen, ROSTER_FROZEN_NOTICE } from '@/features/teams/rosterLock'
import { POSITION_LABELS, toOptions } from '@/lib/labels'
import { reloadOnError } from '@/lib/reloadOnError'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import { POSITIONS, type PlayerProfileResponse, type Position } from '@/types/api'
import { InvitePlayerModal } from '../components/InvitePlayerModal'
import { PlayerCard } from '../components/PlayerCard'
import { useFreeAgents } from '../hooks/usePlayers'
import { canInviteWithTeam, inviteButtonState, pendingInvitedPlayerIds } from '../invitations'

const POSITION_OPTIONS = toOptions(POSITIONS, POSITION_LABELS)

export function FreeAgentsPage() {
  const { user } = useAuth()
  const [position, setPosition] = useState<Position | ''>('')
  const { data, loading, error, refetch } = useFreeAgents(position)

  // Only a viewer who captains an ACTIVE, unlocked team may invite; organizers without a team never do.
  const teamQuery = useTeam(user?.teamId ?? null)
  const team = teamQuery.data
  const canInvite = canInviteWithTeam(team, user?.id)
  const rosterFrozen = isRosterFrozen(team)
  const invitationsQuery = useTeamInvitations(canInvite && team ? team.id : null, 'PENDING')
  const invitedIds = pendingInvitedPlayerIds(invitationsQuery.data)

  const [toInvite, setToInvite] = useState<PlayerProfileResponse | null>(null)

  const invite = useMutation(async (input: { player: PlayerProfileResponse; message: string | null }) => {
    if (!team) throw new Error('No tiene un equipo activo.')
    // The player may have joined a team or been invited meanwhile: on failure reload both lists.
    const created = await reloadOnError(
      () => teamsApi.invite(team.id, { playerId: input.player.userId, message: input.message }),
      { refetch },
      invitationsQuery,
    )
    invitationsQuery.setData((previous) => [created, ...(previous ?? [])])
    return created
  })

  const closeInvite = () => {
    setToInvite(null)
    invite.reset()
  }

  const submitInvite = (message: string | null) => {
    if (!toInvite) return
    const player = toInvite
    invite
      .mutate({ player, message })
      .then(() => {
        toast.success(`Invitación enviada a ${player.fullName}.`)
        closeInvite()
      })
      .catch(() => undefined)
  }

  const renderActions = (player: PlayerProfileResponse) => {
    const state = inviteButtonState(player, canInvite, invitedIds)
    if (state === 'hidden') return undefined
    if (state === 'invited') {
      return (
        <Button size="sm" variant="ghost" disabled>
          Invitación enviada
        </Button>
      )
    }
    return (
      <Button size="sm" variant="outline" onClick={() => setToInvite(player)} disabled={invitationsQuery.loading}>
        Invitar
      </Button>
    )
  }

  return (
    <>
      <PageHeader
        title="Jugadores disponibles"
        description={
          canInvite
            ? 'Jugadores con perfil deportivo que aún no pertenecen a un equipo. Invítelos a unirse al suyo.'
            : 'Jugadores con perfil deportivo que aún no pertenecen a un equipo.'
        }
        actions={
          <label className="flex items-center gap-2 text-sm text-stone-600">
            Posición
            <Select
              options={POSITION_OPTIONS}
              placeholder="Todas"
              value={position}
              onChange={(event) => setPosition(event.target.value as Position | '')}
              className="w-44"
            />
          </label>
        }
      />
      {rosterFrozen && (
        <Alert kind="info" className="mb-4">
          {ROSTER_FROZEN_NOTICE}
        </Alert>
      )}
      <QueryState loading={loading} error={error} onRetry={refetch}>
        {data && data.length === 0 ? (
          <EmptyState
            title="No hay jugadores disponibles"
            description={
              position
                ? `No se encontraron jugadores libres en la posición ${POSITION_LABELS[position].toLowerCase()}.`
                : 'Todos los jugadores con perfil deportivo ya pertenecen a un equipo.'
            }
          />
        ) : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {data?.map((player) => (
              <PlayerCard key={player.userId} player={player} actions={renderActions(player)} />
            ))}
          </div>
        )}
      </QueryState>

      <InvitePlayerModal
        key={toInvite?.userId ?? 'none'}
        player={toInvite}
        teamName={team?.name ?? ''}
        loading={invite.loading}
        error={invite.error}
        fieldErrors={invite.fieldErrors}
        onSubmit={submitInvite}
        onClose={closeInvite}
      />
    </>
  )
}
