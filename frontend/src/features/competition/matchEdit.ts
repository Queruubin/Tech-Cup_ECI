import type { SelectOption } from '@/components/atoms/Select'
import { DEFAULT_KICKOFF_TIME, joinDateTime, splitDateTime, type DateTimeParts } from '@/lib/format'
import type {
  GroupResponse,
  MatchPhase,
  MatchResponse,
  RegistrationResponse,
  UpdateMatchRequest,
  UserResponse,
} from '@/types/api'

/** Pure helpers behind the organizer's match edit form (kept out of the component for testing). */

export const TEAMS_LOCKED_HINT = 'Reabra el partido para cambiar los equipos.'
export const PAST_DATE_HINT = 'La fecha queda en el pasado: úsela para registrar cuándo se jugó realmente.'
export const SAME_TEAMS_ERROR = 'El equipo local y el visitante deben ser distintos.'
export const INVALID_DATE_ERROR = 'Ingrese una fecha y hora válidas.'
export const MISSING_TIME_ERROR = 'Indique la hora.'
export const NO_CHANGES_MESSAGE = 'No hay cambios para guardar.'
/** A group match cannot be reopened once the knockout exists (mirrors the server's 409). */
export const GROUP_STAGE_CLOSED_HINT =
  'La fase de grupos ya terminó: deshaga la eliminatoria antes de reabrir partidos de grupo.'
export const KNOCKOUT_REDRAW_WARNING =
  'La eliminatoria ya fue armada. Si esta corrección cambia los clasificados o su orden, deshaga las fases eliminatorias (Gestión → Deshacer última fase) y vuelva a armarla.'

export interface MatchEditValues {
  /** `<input type="date">` value (local date of the kick-off). */
  scheduledDate: string
  /** `<input type="time">` value (local time of the kick-off). */
  scheduledTime: string
  venueId: string
  refereeId: string
  homeTeamId: string
  awayTeamId: string
}

export type MatchEditErrors = Partial<Record<keyof MatchEditValues, string>>

/** The kick-off part of the values, as `DateTimeFields` and `joinDateTime` expect it. */
export function kickoffOf(values: Pick<MatchEditValues, 'scheduledDate' | 'scheduledTime'>): DateTimeParts {
  return { date: values.scheduledDate, time: values.scheduledTime }
}

export function matchEditValuesFromMatch(match: MatchResponse): MatchEditValues {
  const kickoff = splitDateTime(match.scheduledAt)
  return {
    scheduledDate: kickoff.date,
    // An unscheduled match offers the default kick-off time once a date is picked.
    scheduledTime: kickoff.time || DEFAULT_KICKOFF_TIME,
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

/**
 * Options for the home/away selects of a match of a group: only the teams of that group (the
 * server refuses any other). The teams currently assigned are kept so the selects never go blank.
 */
export function groupTeamOptions(
  groups: readonly GroupResponse[],
  groupId: number,
  current: ReadonlyArray<{ id: number; name: string }> = [],
): SelectOption[] {
  const byId = new Map<number, string>()
  for (const team of groups.find((group) => group.id === groupId)?.teams ?? []) byId.set(team.id, team.name)
  for (const team of current) {
    if (!byId.has(team.id)) byId.set(team.id, team.name)
  }
  return [...byId.entries()]
    .map(([id, name]) => ({ value: String(id), label: name }))
    .sort((a, b) => a.label.localeCompare(b.label, 'es'))
}

/** True when the date and time make a valid instant before `now`. */
export function isPastDateTime(kickoff: DateTimeParts, now: number = Date.now()): boolean {
  const iso = joinDateTime(kickoff)
  return !!iso && new Date(iso).getTime() < now
}

/** Client-side checks. Past dates are allowed: they record when the match was actually played. */
export function validateMatchEdit(values: MatchEditValues): MatchEditErrors {
  const errors: MatchEditErrors = {}
  // An empty date keeps the current kick-off (the API cannot unschedule a match).
  if (values.scheduledDate) {
    if (!values.scheduledTime) errors.scheduledTime = MISSING_TIME_ERROR
    else if (!joinDateTime(kickoffOf(values))) errors.scheduledDate = INVALID_DATE_ERROR
  }
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
  if (values.scheduledDate !== original.scheduledDate || values.scheduledTime !== original.scheduledTime) {
    const iso = joinDateTime(kickoffOf(values))
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
 * were built once (Armar eliminatoria) and do not change when a group result changes.
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
