import { MATCH_PHASES, type MatchPhase, type MatchResponse } from '@/types/api'

/** Spanish noun phrase for a phase, used after "de" ("los partidos de la fase de grupos"). */
const PHASE_NOUN: Record<MatchPhase, string> = {
  GROUP: 'la fase de grupos',
  QUARTERFINAL: 'los cuartos de final',
  SEMIFINAL: 'las semifinales',
  FINAL: 'la final',
}

/** Suffix after "Fase" ("Fase de grupos deshecha", "Fase final deshecha"). */
const PHASE_TITLE: Record<MatchPhase, string> = {
  GROUP: 'de grupos',
  QUARTERFINAL: 'de cuartos de final',
  SEMIFINAL: 'de semifinales',
  FINAL: 'final',
}

export interface UndoPhaseState {
  /** Latest phase present among the matches; `null` when there are no matches. */
  phase: MatchPhase | null
  /** Matches of that phase (all of them are deleted by the undo). */
  count: number
  /** The server refuses (409) when any match of the latest phase was played. */
  enabled: boolean
  /** Why the action is disabled, for a tooltip/hint; `null` when enabled. */
  reason: string | null
}

/** Derives whether "Deshacer última fase" is available from the tournament's matches. */
export function undoPhaseState(matches: readonly Pick<MatchResponse, 'phase' | 'status'>[]): UndoPhaseState {
  let latestIndex = -1
  for (const match of matches) latestIndex = Math.max(latestIndex, MATCH_PHASES.indexOf(match.phase))
  if (latestIndex < 0) return { phase: null, count: 0, enabled: false, reason: 'No hay partidos generados.' }
  const phase: MatchPhase = MATCH_PHASES[latestIndex] as MatchPhase
  const inPhase = matches.filter((match) => match.phase === phase)
  const played = inPhase.some((match) => match.status === 'PLAYED')
  return {
    phase,
    count: inPhase.length,
    enabled: !played,
    reason: played ? `No se puede deshacer ${PHASE_NOUN[phase]}: hay partidos jugados. Reábralos primero.` : null,
  }
}

export function undoPhaseConfirmDescription(phase: MatchPhase, count: number): string {
  const what = count === 1 ? `Se eliminará el partido de ${PHASE_NOUN[phase]}.` : `Se eliminarán los ${count} partidos de ${PHASE_NOUN[phase]}.`
  const regenerate = phase === 'GROUP' ? 'Generar fixture' : 'Avanzar fase'
  return `${what} Podrá volver a generar la fase con ${regenerate}.`
}

export function undoPhaseSuccessMessage(phase: MatchPhase, deletedMatches: number): string {
  const matches = deletedMatches === 1 ? '1 partido eliminado' : `${deletedMatches} partidos eliminados`
  return `Fase ${PHASE_TITLE[phase]} deshecha: ${matches}.`
}
