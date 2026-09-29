import type { EventType, MatchEventRequest, MatchPhase, MatchResponse, MatchResultRequest } from '@/types/api'

/** Knockout phases accept penalties; the group stage does not. */
export function isKnockoutPhase(phase: MatchPhase): boolean {
  return phase !== 'GROUP'
}

/** Team that advanced by walkover after a knockout cancellation, or null. */
export function walkoverWinnerOf(match: MatchResponse): MatchResponse['homeTeam'] | null {
  if (match.walkoverWinnerTeamId === null) return null
  if (match.walkoverWinnerTeamId === match.homeTeam.id) return match.homeTeam
  if (match.walkoverWinnerTeamId === match.awayTeam.id) return match.awayTeam
  return null
}

// ---------------------------------------------------------------------------
// Result form
// ---------------------------------------------------------------------------

export type MatchSide = 'home' | 'away'

/** One editable event row. Values are strings because they come straight from form controls. */
export interface ResultEventDraft {
  /** Client-side row key; never sent to the API. */
  key: string
  side: MatchSide | ''
  playerId: string
  type: EventType | ''
  minute: string
}

export interface ResultFormValues {
  homeScore: string
  awayScore: string
  homePenalties: string
  awayPenalties: string
  events: ResultEventDraft[]
}

export type ResultScoreField = 'homeScore' | 'awayScore' | 'homePenalties' | 'awayPenalties'

export interface ResultValidation {
  /** Errors on the score inputs. */
  errors: Partial<Record<ResultScoreField, string>>
  /** Errors per event row, keyed by `ResultEventDraft.key`. */
  eventErrors: Record<string, string>
  valid: boolean
}

export const EMPTY_RESULT_VALUES: ResultFormValues = {
  homeScore: '',
  awayScore: '',
  homePenalties: '',
  awayPenalties: '',
  events: [],
}

const MAX_MINUTE = 130

/**
 * Form values pre-filled from a recorded result, used to correct a PLAYED match.
 * Event rows keep a stable key derived from the event id.
 */
export function resultValuesFromMatch(match: MatchResponse): ResultFormValues {
  const numberOrEmpty = (value: number | null) => (value === null ? '' : String(value))
  return {
    homeScore: numberOrEmpty(match.homeScore),
    awayScore: numberOrEmpty(match.awayScore),
    homePenalties: numberOrEmpty(match.homePenalties),
    awayPenalties: numberOrEmpty(match.awayPenalties),
    events: match.events.map((event) => ({
      key: `existing-${event.id}`,
      side: event.teamId === match.awayTeam.id ? 'away' : 'home',
      playerId: String(event.playerId),
      type: event.type,
      minute: numberOrEmpty(event.minute),
    })),
  }
}

function parseCount(raw: string): number | null {
  const value = raw.trim()
  if (!value) return null
  const parsed = Number(value)
  if (!Number.isInteger(parsed) || parsed < 0) return null
  return parsed
}

/** Number of GOAL rows registered for a side. */
export function goalsFor(events: ResultEventDraft[], side: MatchSide): number {
  return events.filter((event) => event.side === side && event.type === 'GOAL').length
}

/**
 * Validates the result form. The core rule (docs/ARCHITECTURE.md 3.4, competition):
 * the goal events of each team must match that team's score.
 */
export function validateResult(values: ResultFormValues, phase: MatchPhase): ResultValidation {
  const errors: Partial<Record<ResultScoreField, string>> = {}
  const eventErrors: Record<string, string> = {}

  const homeScore = parseCount(values.homeScore)
  const awayScore = parseCount(values.awayScore)
  if (homeScore === null) errors.homeScore = 'Ingrese un marcador válido (0 o más).'
  if (awayScore === null) errors.awayScore = 'Ingrese un marcador válido (0 o más).'

  for (const event of values.events) {
    if (!event.side) {
      eventErrors[event.key] = 'Seleccione el equipo.'
      continue
    }
    if (!event.playerId) {
      eventErrors[event.key] = 'Seleccione el jugador.'
      continue
    }
    if (!event.type) {
      eventErrors[event.key] = 'Seleccione el tipo de evento.'
      continue
    }
    if (event.minute.trim()) {
      const minute = Number(event.minute)
      if (!Number.isInteger(minute) || minute < 0 || minute > MAX_MINUTE) {
        eventErrors[event.key] = `El minuto debe ser un número entre 0 y ${MAX_MINUTE}.`
      }
    }
  }

  const homeGoals = goalsFor(values.events, 'home')
  const awayGoals = goalsFor(values.events, 'away')
  if (homeScore !== null && homeGoals !== homeScore) {
    errors.homeScore = `Los goles registrados para el equipo local (${homeGoals}) no coinciden con el marcador (${homeScore}).`
  }
  if (awayScore !== null && awayGoals !== awayScore) {
    errors.awayScore = `Los goles registrados para el equipo visitante (${awayGoals}) no coinciden con el marcador (${awayScore}).`
  }

  const knockout = isKnockoutPhase(phase)
  const homePenalties = parseCount(values.homePenalties)
  const awayPenalties = parseCount(values.awayPenalties)
  const homePenaltiesFilled = values.homePenalties.trim() !== ''
  const awayPenaltiesFilled = values.awayPenalties.trim() !== ''

  if (!knockout) {
    if (homePenaltiesFilled) errors.homePenalties = 'Los penales solo aplican en fases eliminatorias.'
    if (awayPenaltiesFilled) errors.awayPenalties = 'Los penales solo aplican en fases eliminatorias.'
  } else {
    if (homePenaltiesFilled && homePenalties === null) errors.homePenalties = 'Ingrese un valor válido (0 o más).'
    if (awayPenaltiesFilled && awayPenalties === null) errors.awayPenalties = 'Ingrese un valor válido (0 o más).'
    if (homePenaltiesFilled !== awayPenaltiesFilled) {
      const missing: ResultScoreField = homePenaltiesFilled ? 'awayPenalties' : 'homePenalties'
      errors[missing] = 'Registre los penales de ambos equipos.'
    }
    const draw = homeScore !== null && awayScore !== null && homeScore === awayScore
    if (draw && !homePenaltiesFilled && !awayPenaltiesFilled) {
      errors.homePenalties = 'En fase eliminatoria un empate debe definirse por penales.'
      errors.awayPenalties = 'En fase eliminatoria un empate debe definirse por penales.'
    }
    if (draw && homePenalties !== null && awayPenalties !== null && homePenalties === awayPenalties) {
      errors.homePenalties = 'Los penales no pueden quedar empatados.'
      errors.awayPenalties = 'Los penales no pueden quedar empatados.'
    }
  }

  return {
    errors,
    eventErrors,
    valid: Object.keys(errors).length === 0 && Object.keys(eventErrors).length === 0,
  }
}

/** Maps validated form values to the API payload. Call only after `validateResult` reports `valid`. */
export function toResultRequest(
  values: ResultFormValues,
  teamIds: Record<MatchSide, number>,
  phase: MatchPhase,
): MatchResultRequest {
  const events: MatchEventRequest[] = values.events.map((event) => {
    const minute = event.minute.trim()
    return {
      teamId: teamIds[event.side === 'away' ? 'away' : 'home'],
      playerId: Number(event.playerId),
      type: event.type as EventType,
      ...(minute ? { minute: Number(minute) } : {}),
    }
  })

  const payload: MatchResultRequest = {
    homeScore: Number(values.homeScore),
    awayScore: Number(values.awayScore),
    events,
  }
  if (isKnockoutPhase(phase) && values.homePenalties.trim() && values.awayPenalties.trim()) {
    payload.homePenalties = Number(values.homePenalties)
    payload.awayPenalties = Number(values.awayPenalties)
  }
  return payload
}

// ---------------------------------------------------------------------------
// Lineup form
// ---------------------------------------------------------------------------

/** Seven-a-side: a lineup has exactly seven starters (docs/ARCHITECTURE.md 3.4). */
export const LINEUP_STARTERS = 7

/** Returns an error message when the selection is not a valid lineup, otherwise `undefined`. */
export function validateLineup(starterIds: readonly number[]): string | undefined {
  if (new Set(starterIds).size !== starterIds.length) {
    return 'La alineación tiene jugadores repetidos.'
  }
  if (starterIds.length !== LINEUP_STARTERS) {
    return `Debe seleccionar exactamente ${LINEUP_STARTERS} titulares (seleccionados: ${starterIds.length}).`
  }
  return undefined
}
