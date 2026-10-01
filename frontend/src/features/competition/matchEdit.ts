import type { SelectOption } from '@/components/atoms/Select'
import { fromDateTimeLocal, toDateTimeLocal } from '@/lib/format'
import type { MatchPhase, MatchResponse, RegistrationResponse, UpdateMatchRequest, UserResponse } from '@/types/api'

/** Pure helpers behind the organizer's match edit form (kept out of the component for testing). */

export const TEAMS_LOCKED_HINT = 'Reabra el partido para cambiar los equipos.'
export const PAST_DATE_HINT = 'La fecha queda en el pasado: úsela para registrar cuándo se jugó realmente.'
export const SAME_TEAMS_ERROR = 'El equipo local y el visitante deben ser distintos.'
export const INVALID_DATE_ERROR = 'Ingrese una fecha y hora válidas.'
export const NO_CHANGES_MESSAGE = 'No hay cambios para guardar.'
export const KNOCKOUT_REDRAW_WARNING =
  'Las eliminatorias ya se generaron a partir de la tabla. Si esta corrección cambia los clasificados o su orden, deshaga las fases eliminatorias (Gestión → Deshacer última fase) y vuelva a avanzar.'

export interface MatchEditValues {
  /** `<input type="datetime-local">` value. */
  scheduledAt: string
  venueId: string
  refereeId: string
  homeTeamId: string
  awayTeamId: string
}

export type MatchEditErrors = Partial<Record<keyof MatchEditValues, string>>

export function matchEditValuesFromMatch(match: MatchResponse): MatchEditValues {
  return {
    scheduledAt: toDateTimeLocal(match.scheduledAt),
    venueId: match.venue ? String(match.venue.id) : '',
    refereeId: match.referee ? String(match.referee.id) : '',
    homeTeamId: String(match.homeTeam.id),
    awayTeamId: String(match.awayTeam.id),
  }
}

/** Whether the team selects are locked and which hint explains it. Teams change only while the match is not PLAYED. */
export function teamsSelectState(match: Pick<MatchResponse, 'teamsEditable'>): { disabled: boolean; hint: string | null } {
  return match.teamsEditable ? { disabled: false, hint: null } : { disabled: true, hint: TEAMS_LOCKED_HINT }
}

/**
 * Options for the home/away selects: the tournament's APPROVED teams. The teams currently assigned
 * are always kept so the selects never show a blank value.
 */
export function approvedTeamOptions(
  registrations: readonly RegistrationResponse[],
  current: ReadonlyArray<{ id: number; name: string }> = [],
): SelectOption[] {
  const byId = new Map<number, string>()
  for (const registration of registrations) {
    if (registration.status === 'APPROVED') byId.set(registration.teamId, registration.teamName)
  }
  for (const team of current) {
    if (!byId.has(team.id)) byId.set(team.id, team.name)
  }
  return [...byId.entries()]
    .map(([id, name]) => ({ value: String(id), label: name }))
    .sort((a, b) => a.label.localeCompare(b.label, 'es'))
}

/** True when a `datetime-local` value is a valid instant before `now`. */
export function isPastDateTime(localValue: string, now: number = Date.now()): boolean {
  const iso = fromDateTimeLocal(localValue)
  return !!iso && new Date(iso).getTime() < now
}

/** Client-side checks. Past dates are allowed: they record when the match was actually played. */
export function validateMatchEdit(values: MatchEditValues): MatchEditErrors {
  const errors: MatchEditErrors = {}
  if (values.scheduledAt && !fromDateTimeLocal(values.scheduledAt)) errors.scheduledAt = INVALID_DATE_ERROR
  if (values.homeTeamId && values.homeTeamId === values.awayTeamId) errors.awayTeamId = SAME_TEAMS_ERROR
  return errors
}

/**
 * Options for the referee select: only ACTIVE referees can be assigned, but the currently assigned
 * one is always kept (even if inactive) so the select never shows a blank value.
 */
export function refereeOptions(
  referees: readonly Pick<UserResponse, 'id' | 'fullName' | 'status'>[],
  current: { id: number; fullName: string } | null = null,
): SelectOption[] {
  const byId = new Map<number, string>()
  for (const referee of referees) {
    if (referee.status === 'ACTIVE') byId.set(referee.id, referee.fullName)
  }
  if (current && !byId.has(current.id)) {
    const listed = referees.find((referee) => referee.id === current.id)
    byId.set(current.id, listed ? `${listed.fullName} (inactivo)` : current.fullName)
  }
  return [...byId.entries()]
    .map(([id, name]) => ({ value: String(id), label: name }))
    .sort((a, b) => a.label.localeCompare(b.label, 'es'))
}

/**
 * Builds the PATCH body with ONLY the fields that differ from the match: omitted fields stay unchanged
 * on the server, and resending an unchanged referee would fail once that referee was inactivated.
 * Teams are only sent while the match still allows it (a PLAYED match answers 409 to any team field).
 * An empty object means there is nothing to save.
 */
export function toUpdateMatchRequest(match: MatchResponse, values: MatchEditValues): UpdateMatchRequest {
  const original = matchEditValuesFromMatch(match)
  const payload: UpdateMatchRequest = {}
  if (values.scheduledAt !== original.scheduledAt) {
    const iso = fromDateTimeLocal(values.scheduledAt)
    if (iso) payload.scheduledAt = iso
  }
  if (values.venueId && values.venueId !== original.venueId) payload.venueId = Number(values.venueId)
  if (values.refereeId && values.refereeId !== original.refereeId) payload.refereeId = Number(values.refereeId)
  if (match.teamsEditable) {
    if (values.homeTeamId && values.homeTeamId !== original.homeTeamId) payload.homeTeamId = Number(values.homeTeamId)
    if (values.awayTeamId && values.awayTeamId !== original.awayTeamId) payload.awayTeamId = Number(values.awayTeamId)
  }
  return payload
}

export function hasMatchChanges(payload: UpdateMatchRequest): boolean {
  return Object.keys(payload).length > 0
}

/**
 * True when `match` is a group match and the tournament already has knockout matches: those pairings
 * were drawn from the standings and are not re-drawn when a group result changes.
 */
export function knockoutDrawnFromGroups(
  match: Pick<MatchResponse, 'phase'>,
  tournamentMatches: readonly Pick<MatchResponse, 'phase'>[] | null | undefined,
): boolean {
  return match.phase === 'GROUP' && !!tournamentMatches?.some((other) => other.phase !== 'GROUP')
}

/**
 * Knockout corrections may move the winner into the next phase: the organizer should check the bracket.
 * A group result saved after the knockout was drawn may change the qualifiers: warn instead.
 */
export function resultSavedMessage(phase: MatchPhase, isCorrection: boolean, knockoutDrawn = false): string {
  if (phase === 'GROUP' && knockoutDrawn) return KNOCKOUT_REDRAW_WARNING
  if (!isCorrection) return 'Resultado registrado.'
  if (phase === 'GROUP') return 'Resultado corregido.'
  return 'Resultado corregido. Revise las llaves: el ganador se actualizó en la siguiente fase si correspondía.'
}
