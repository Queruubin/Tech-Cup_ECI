import { Link } from 'react-router'
import { EmptyState } from '@/components/molecules/EmptyState'
import { cn } from '@/lib/cn'
import { formatDateTime } from '@/lib/format'
import { MATCH_PHASE_LABELS } from '@/lib/labels'
import { MATCH_PHASES, type BracketPhase, type MatchPhase, type MatchResponse } from '@/types/api'

export interface BracketViewProps {
  phases: BracketPhase[]
  /** Builds the link for a match box. Omit to render without links. */
  matchHref?: (match: MatchResponse) => string
}

const KNOCKOUT_PHASES: MatchPhase[] = ['QUARTERFINAL', 'SEMIFINAL', 'FINAL']

/** Returns the winning team id of a knockout match (walkover, score, then penalties), or null. */
export function matchWinnerId(match: MatchResponse): number | null {
  if (match.status === 'CANCELLED') return match.walkoverWinnerTeamId
  if (match.status !== 'PLAYED' || match.homeScore === null || match.awayScore === null) return null
  if (match.homeScore !== match.awayScore) return match.homeScore > match.awayScore ? match.homeTeam.id : match.awayTeam.id
  if (match.homePenalties !== null && match.awayPenalties !== null && match.homePenalties !== match.awayPenalties) {
    return match.homePenalties > match.awayPenalties ? match.homeTeam.id : match.awayTeam.id
  }
  return null
}

function TeamLine({ name, score, penalties, winner }: { name: string; score: number | null; penalties: number | null; winner: boolean }) {
  return (
    <div className={cn('flex items-center justify-between gap-2 px-3 py-1.5 text-sm', winner ? 'font-semibold text-ink' : 'text-stone-600')}>
      <span className="truncate">{name}</span>
      <span className="shrink-0 tabular-nums">
        {score ?? '–'}
        {penalties !== null && <span className="ml-1 text-[11px] text-stone-400">({penalties})</span>}
      </span>
    </div>
  )
}

function MatchBox({ match, href }: { match: MatchResponse; href?: string }) {
  const winner = matchWinnerId(match)
  const content = (
    <div
      className={cn(
        'w-56 divide-y divide-stone-100 rounded-xl border bg-white shadow-sm',
        match.status === 'CANCELLED' ? 'border-brand-200 opacity-70' : 'border-stone-200',
        href && 'transition hover:border-brand-300',
      )}
    >
      <TeamLine name={match.homeTeam.name} score={match.homeScore} penalties={match.homePenalties} winner={winner === match.homeTeam.id} />
      <TeamLine name={match.awayTeam.name} score={match.awayScore} penalties={match.awayPenalties} winner={winner === match.awayTeam.id} />
      <div className="px-3 py-1 text-[11px] text-stone-400">
        {match.status === 'CANCELLED'
          ? match.walkoverWinnerTeamId !== null
            ? 'Cancelado · walkover'
            : 'Cancelado'
          : formatDateTime(match.scheduledAt)}
      </div>
    </div>
  )
  return href ? (
    <Link to={href} className="block">
      {content}
    </Link>
  ) : (
    content
  )
}

/** Tournament bracket: group-stage rounds as lists, knockout phases as columns. */
export function BracketView({ phases, matchHref }: BracketViewProps) {
  const byPhase = new Map<MatchPhase, MatchResponse[]>()
  for (const phase of phases) byPhase.set(phase.phase, phase.matches)

  const groupMatches = byPhase.get('GROUP') ?? []
  const knockout = KNOCKOUT_PHASES.filter((phase) => (byPhase.get(phase) ?? []).length > 0)

  if (groupMatches.length === 0 && knockout.length === 0) {
    return (
      <EmptyState
        title="Llaves no disponibles"
        description="El fixture se genera cuando el torneo está en progreso y hay equipos aprobados."
      />
    )
  }

  const rounds = new Map<number, MatchResponse[]>()
  for (const match of groupMatches) {
    const list = rounds.get(match.roundNumber) ?? []
    list.push(match)
    rounds.set(match.roundNumber, list)
  }
  const roundNumbers = [...rounds.keys()].sort((a, b) => a - b)

  return (
    <div className="flex flex-col gap-8">
      {groupMatches.length > 0 && (
        <section>
          <h3 className="mb-3 text-sm font-semibold uppercase tracking-wide text-stone-500">{MATCH_PHASE_LABELS.GROUP}</h3>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            {roundNumbers.map((round) => (
              <div key={round} className="rounded-2xl border border-stone-200 bg-stone-50 p-3">
                <p className="mb-2 text-xs font-semibold text-stone-600">Jornada {round}</p>
                <div className="flex flex-col gap-2">
                  {(rounds.get(round) ?? []).map((match) => (
                    <MatchBox key={match.id} match={match} href={matchHref?.(match)} />
                  ))}
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

      {knockout.length > 0 && (
        <section>
          <h3 className="mb-3 text-sm font-semibold uppercase tracking-wide text-stone-500">Fase eliminatoria</h3>
          <div className="overflow-x-auto pb-2">
            <div className="flex min-w-max items-stretch gap-8">
              {MATCH_PHASES.filter((phase): phase is Exclude<MatchPhase, 'GROUP'> => knockout.includes(phase)).map((phase) => (
                <div key={phase} className="flex flex-col" data-testid={`bracket-phase-${phase}`}>
                  <p className="mb-3 text-center text-xs font-semibold uppercase tracking-wide text-brand-700">
                    {MATCH_PHASE_LABELS[phase]}
                  </p>
                  <div className="flex flex-1 flex-col justify-around gap-4">
                    {(byPhase.get(phase) ?? []).map((match) => (
                      <MatchBox key={match.id} match={match} href={matchHref?.(match)} />
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </section>
      )}
    </div>
  )
}
