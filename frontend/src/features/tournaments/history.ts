import type { PodiumSource, TournamentHistory } from '@/types/api'

/** Pure helpers behind the history of a finished tournament (kept out of the components for testing). */

const PODIUM_LABELS: Record<number, string> = { 1: 'Campeón', 2: 'Subcampeón', 3: '3er puesto' }

/** "Campeón", "Subcampeón", "3er puesto". */
export function podiumLabel(position: number): string {
  return PODIUM_LABELS[position] ?? `${position}° puesto`
}

/** Where the podium comes from, in one sentence (null when nobody played). */
export function podiumSourceHint(source: PodiumSource | null): string | null {
  if (source === 'KNOCKOUT') {
    return 'Campeón y subcampeón según la final. El 3er puesto es el mejor eliminado en semifinales, comparando todos sus partidos del torneo: puntos, diferencia de gol y goles a favor.'
  }
  if (source === 'STANDINGS') return 'No se jugó una final: el podio sale de la tabla general.'
  return null
}

/** Goals conceded per match with one decimal, e.g. "0,5". */
export function goalsPerMatch(goals: number, matches: number): string {
  if (matches <= 0) return '0'
  return (goals / matches).toLocaleString('es-CO', { maximumFractionDigits: 2 })
}

/** Teams staff may still record as sanctioned: participants not sanctioned yet, by name. */
export function sanctionCandidates(history: Pick<TournamentHistory, 'participants' | 'sanctionedTeams'>) {
  const sanctioned = new Set(history.sanctionedTeams.map((team) => team.teamId).filter((id): id is number => id !== null))
  return history.participants.filter((team) => !sanctioned.has(team.id))
}

/** Where the organizer lands after finishing a tournament from "Gestión". */
export function historyPath(tournamentId: number): string {
  return `/tournaments/${tournamentId}?tab=history`
}

/** Router state that tells the history tab the tournament was just finished (shows the hint). */
export interface HistoryLocationState {
  justFinished?: boolean
}
