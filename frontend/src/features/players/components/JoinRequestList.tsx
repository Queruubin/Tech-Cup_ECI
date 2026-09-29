import type { ReactNode } from 'react'
import { StatusBadge } from '@/components/atoms/Badge'
import { EmptyState } from '@/components/molecules/EmptyState'
import { formatDateTime } from '@/lib/format'
import { POSITION_LABELS } from '@/lib/labels'
import type { JoinRequestResponse } from '@/types/api'

export interface JoinRequestListProps {
  requests: JoinRequestResponse[]
  /** 'player' shows the team name; 'captain' shows the player. */
  perspective: 'player' | 'captain'
  renderActions?: (request: JoinRequestResponse) => ReactNode
  emptyTitle?: string
  emptyDescription?: ReactNode
  emptyAction?: ReactNode
}

export function JoinRequestList({
  requests,
  perspective,
  renderActions,
  emptyTitle = 'Sin solicitudes',
  emptyDescription,
  emptyAction,
}: JoinRequestListProps) {
  if (requests.length === 0) {
    return <EmptyState title={emptyTitle} description={emptyDescription} action={emptyAction} />
  }
  return (
    <ul className="divide-y divide-stone-100 rounded-2xl border border-stone-200 bg-white">
      {requests.map((request) => (
        <li key={request.id} className="flex flex-col gap-2 px-4 py-3 sm:flex-row sm:items-center">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <p className="text-sm font-medium text-ink">
                {perspective === 'player' ? request.teamName : request.playerName}
              </p>
              <StatusBadge kind="joinRequest" value={request.status} />
            </div>
            <p className="mt-0.5 text-xs text-stone-500">
              {perspective === 'captain' && (
                <>
                  {POSITION_LABELS[request.position]} · Dorsal {request.jerseyNumber} ·{' '}
                </>
              )}
              Enviada el {formatDateTime(request.createdAt)}
            </p>
            {request.message && <p className="mt-1 text-sm text-stone-700">“{request.message}”</p>}
          </div>
          {renderActions && <div className="flex shrink-0 gap-2">{renderActions(request)}</div>}
        </li>
      ))}
    </ul>
  )
}
