import type { ReactNode } from 'react'
import { Avatar } from '@/components/atoms/Avatar'
import { Badge } from '@/components/atoms/Badge'
import { POSITION_LABELS } from '@/lib/labels'
import type { PlayerProfileResponse } from '@/types/api'

export interface PlayerCardProps {
  player: PlayerProfileResponse
  /** Optional actions (e.g. the captain's "Invitar" button). */
  actions?: ReactNode
}

export function PlayerCard({ player, actions }: PlayerCardProps) {
  return (
    <article className="flex items-center gap-3 rounded-2xl border border-stone-200 bg-white p-4 shadow-sm">
      <Avatar name={player.fullName} photoFileId={player.photoFileId} size="lg" />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold text-ink">{player.fullName}</p>
        <div className="mt-1 flex flex-wrap items-center gap-1.5">
          <Badge tone="info">{POSITION_LABELS[player.position]}</Badge>
          <Badge tone="neutral">Dorsal {player.jerseyNumber}</Badge>
          {player.teamName ? (
            <Badge tone="success">{player.teamName}</Badge>
          ) : (
            <Badge tone="warning">Sin equipo</Badge>
          )}
        </div>
      </div>
      {actions && <div className="shrink-0">{actions}</div>}
    </article>
  )
}
