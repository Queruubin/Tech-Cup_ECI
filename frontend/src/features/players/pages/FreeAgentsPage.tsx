import { useState } from 'react'
import { Select } from '@/components/atoms/Select'
import { EmptyState } from '@/components/molecules/EmptyState'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { POSITION_LABELS, toOptions } from '@/lib/labels'
import { POSITIONS, type Position } from '@/types/api'
import { PlayerCard } from '../components/PlayerCard'
import { useFreeAgents } from '../hooks/usePlayers'

const POSITION_OPTIONS = toOptions(POSITIONS, POSITION_LABELS)

export function FreeAgentsPage() {
  const [position, setPosition] = useState<Position | ''>('')
  const { data, loading, error, refetch } = useFreeAgents(position)

  return (
    <>
      <PageHeader
        title="Jugadores disponibles"
        description="Jugadores con perfil deportivo que aún no pertenecen a un equipo."
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
              <PlayerCard key={player.userId} player={player} />
            ))}
          </div>
        )}
      </QueryState>
    </>
  )
}
