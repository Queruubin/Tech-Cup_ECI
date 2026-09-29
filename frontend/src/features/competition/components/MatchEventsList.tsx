import { cn } from '@/lib/cn'
import { EVENT_TYPE_LABELS } from '@/lib/labels'
import type { EventType, MatchEvent, MatchTeam } from '@/types/api'

const EVENT_ORDER: Record<EventType, number> = { GOAL: 0, YELLOW_CARD: 1, RED_CARD: 2 }

function EventIcon({ type }: { type: EventType }) {
  if (type === 'GOAL') {
    return (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" className="h-4 w-4 text-brand-600" aria-hidden="true">
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7.5l3.2 2.3-1.2 3.8H10l-1.2-3.8L12 7.5z" />
      </svg>
    )
  }
  return (
    <span
      className={cn('block h-4 w-3 rounded-[2px]', type === 'YELLOW_CARD' ? 'bg-amber-400' : 'bg-brand-600')}
      aria-hidden="true"
    />
  )
}

function EventRow({ event }: { event: MatchEvent }) {
  return (
    <li className="flex items-center gap-2 py-1.5 text-sm">
      <span className="flex h-5 w-5 shrink-0 items-center justify-center">
        <EventIcon type={event.type} />
      </span>
      <span className="min-w-0 flex-1 truncate text-stone-800">{event.playerName}</span>
      <span className="shrink-0 text-xs text-stone-500">{EVENT_TYPE_LABELS[event.type]}</span>
      <span className="w-10 shrink-0 text-right text-xs tabular-nums text-stone-400">
        {event.minute !== null ? `${event.minute}'` : '—'}
      </span>
    </li>
  )
}

export interface MatchEventsListProps {
  events: MatchEvent[]
  homeTeam: MatchTeam
  awayTeam: MatchTeam
}

/** Match events grouped by team, ordered goals → yellow cards → red cards, then by minute. */
export function MatchEventsList({ events, homeTeam, awayTeam }: MatchEventsListProps) {
  if (events.length === 0) {
    return <p className="text-sm text-stone-500">No se registraron eventos en este partido.</p>
  }

  const sortEvents = (list: MatchEvent[]) =>
    [...list].sort(
      (a, b) => EVENT_ORDER[a.type] - EVENT_ORDER[b.type] || (a.minute ?? Number.MAX_SAFE_INTEGER) - (b.minute ?? Number.MAX_SAFE_INTEGER),
    )

  const columns = [homeTeam, awayTeam].map((team) => ({
    team,
    events: sortEvents(events.filter((event) => event.teamId === team.id)),
  }))

  return (
    <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
      {columns.map(({ team, events: teamEvents }) => (
        <div key={team.id}>
          <h4 className="mb-2 border-b border-stone-100 pb-1.5 text-sm font-semibold text-ink">{team.name}</h4>
          {teamEvents.length === 0 ? (
            <p className="text-sm text-stone-500">Sin eventos.</p>
          ) : (
            <ul className="divide-y divide-stone-50">
              {teamEvents.map((event) => (
                <EventRow key={event.id} event={event} />
              ))}
            </ul>
          )}
        </div>
      ))}
    </div>
  )
}
