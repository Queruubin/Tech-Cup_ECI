import { LineupPitch } from '@/components/organisms/LineupPitch'
import { POSITION_LABELS } from '@/lib/labels'
import type { LineupPlayer, LineupResponse } from '@/types/api'

function PlayerList({ title, players, emptyMessage }: { title: string; players: LineupPlayer[]; emptyMessage: string }) {
  return (
    <div>
      <h4 className="mb-2 text-sm font-semibold text-ink">{title}</h4>
      {players.length === 0 ? (
        <p className="text-sm text-stone-500">{emptyMessage}</p>
      ) : (
        <ul className="divide-y divide-stone-100 rounded-2xl border border-stone-200 bg-white">
          {players.map((player) => (
            <li key={player.userId} className="flex items-center gap-3 px-4 py-2.5">
              <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-ink text-xs font-semibold tabular-nums text-white">
                {player.jerseyNumber}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-ink">{player.fullName}</span>
                <span className="block text-xs text-stone-500">{POSITION_LABELS[player.position]}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export interface LineupViewProps {
  lineup: LineupResponse
}

/** Read-only lineup: pitch preview plus starters and substitutes. */
export function LineupView({ lineup }: LineupViewProps) {
  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
      <div className="flex flex-col gap-6">
        <PlayerList title="Titulares" players={lineup.starters} emptyMessage="Sin titulares registrados." />
        <PlayerList title="Suplentes" players={lineup.substitutes} emptyMessage="Sin suplentes." />
      </div>
      <div>
        <LineupPitch formation={lineup.formation} starters={lineup.starters} />
      </div>
    </div>
  )
}
