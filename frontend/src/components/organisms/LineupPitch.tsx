import { cn } from '@/lib/cn'
import { FORMATION_LABELS } from '@/lib/labels'
import type { Formation, LineupPlayer, Position } from '@/types/api'

export interface LineupPitchProps {
  formation: Formation
  starters: LineupPlayer[]
  className?: string
}

/** `F_2_3_1` → `[2, 3, 1]` (defenders, midfielders, forwards). */
export function formationRows(formation: Formation): number[] {
  return formation
    .slice(2)
    .split('_')
    .map((part) => Number(part))
}

const POSITION_ORDER: Record<Position, number> = { GOALKEEPER: 3, DEFENDER: 0, MIDFIELDER: 1, FORWARD: 2 }

export interface ArrangedLineup {
  goalkeeper: LineupPlayer | null
  /** Rows from defence to attack; `null` marks an empty slot. */
  rows: (LineupPlayer | null)[][]
}

/** Places starters on the formation: the goalkeeper first, then outfield players by position order. */
export function arrangeLineup(formation: Formation, starters: LineupPlayer[]): ArrangedLineup {
  const goalkeeper = starters.find((player) => player.position === 'GOALKEEPER') ?? null
  const outfield = starters
    .filter((player) => player !== goalkeeper)
    .sort((a, b) => POSITION_ORDER[a.position] - POSITION_ORDER[b.position] || a.jerseyNumber - b.jerseyNumber)

  let index = 0
  const rows = formationRows(formation).map((size) =>
    Array.from({ length: size }, () => {
      const player = outfield[index] ?? null
      index += 1
      return player
    }),
  )
  return { goalkeeper, rows }
}

function PlayerChip({ player, goalkeeper }: { player: LineupPlayer | null; goalkeeper?: boolean }) {
  return (
    <div className="flex w-16 flex-col items-center gap-1">
      <span
        className={cn(
          'flex h-9 w-9 items-center justify-center rounded-full text-sm font-bold shadow',
          player ? (goalkeeper ? 'bg-amber-400 text-ink' : 'bg-white text-emerald-800') : 'border-2 border-dashed border-white/60 text-white/60',
        )}
      >
        {player ? player.jerseyNumber : '?'}
      </span>
      <span className="w-full truncate text-center text-[10px] font-medium leading-tight text-white drop-shadow">
        {player ? player.fullName.split(' ')[0] : 'Libre'}
      </span>
    </div>
  )
}

/** CSS-only football pitch with the starters placed according to the formation. */
export function LineupPitch({ formation, starters, className }: LineupPitchProps) {
  const { goalkeeper, rows } = arrangeLineup(formation, starters)
  const rowsTopToBottom = [...rows].reverse()

  return (
    <div className={cn('mx-auto w-full max-w-sm', className)}>
      <div
        className="relative aspect-[3/4] overflow-hidden rounded-2xl bg-emerald-600 shadow-inner"
        role="img"
        aria-label={`Alineación ${FORMATION_LABELS[formation]}`}
      >
        {/* pitch markings */}
        <div className="absolute inset-3 rounded-lg border-2 border-white/60" aria-hidden="true" />
        <div className="absolute inset-x-3 top-1/2 border-t-2 border-white/60" aria-hidden="true" />
        <div className="absolute left-1/2 top-1/2 h-20 w-20 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white/60" aria-hidden="true" />
        <div className="absolute left-1/2 top-3 h-14 w-36 -translate-x-1/2 border-2 border-t-0 border-white/60" aria-hidden="true" />
        <div className="absolute bottom-3 left-1/2 h-14 w-36 -translate-x-1/2 border-2 border-b-0 border-white/60" aria-hidden="true" />

        <div className="relative flex h-full flex-col justify-between px-4 py-6">
          {rowsTopToBottom.map((row, rowIndex) => (
            <div key={rowIndex} className="flex justify-around">
              {row.map((player, slot) => (
                <PlayerChip key={player?.userId ?? `empty-${rowIndex}-${slot}`} player={player} />
              ))}
            </div>
          ))}
          <div className="flex justify-center">
            <PlayerChip player={goalkeeper} goalkeeper />
          </div>
        </div>
      </div>
      <p className="mt-2 text-center text-xs text-stone-500">Formación {FORMATION_LABELS[formation]}</p>
    </div>
  )
}
