import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { BracketPhase, MatchPhase, MatchResponse } from '@/types/api'
import { BracketView } from './BracketView'
import { orderKnockoutPhases } from './bracketOrder'

/** Knockout drawing: the two matches feeding the same next-round match are drawn together. */

function team(id: number) {
  return { id, name: `Equipo ${id}`, colors: 'Rojo' }
}

function played(id: number, phase: MatchPhase, home: number, away: number, link?: { next: number; slot: 'HOME' | 'AWAY' }): MatchResponse {
  return {
    id,
    tournamentId: 1,
    phase,
    roundNumber: 1,
    group: null,
    homeTeam: team(home),
    awayTeam: team(away),
    venue: null,
    referee: null,
    scheduledAt: '2026-10-10T23:00:00Z',
    status: 'PLAYED',
    homeScore: 1,
    awayScore: 0,
    homePenalties: null,
    awayPenalties: null,
    cancelReason: null,
    walkoverWinnerTeamId: null,
    resultEditable: true,
    teamsEditable: false,
    reopenable: true,
    nextMatchId: link?.next ?? null,
    nextSlot: link?.slot ?? null,
    events: [],
  }
}

function scheduled(id: number, phase: MatchPhase, home: number, away: number): MatchResponse {
  return { ...played(id, phase, home, away), status: 'SCHEDULED', homeScore: null, awayScore: null, reopenable: false }
}

// Standard bracket order of the suggestion: 1v8, 4v5, 3v6, 2v7 (home team wins every match).
const LINKED: BracketPhase[] = [
  {
    phase: 'QUARTERFINAL',
    matches: [
      played(11, 'QUARTERFINAL', 1, 8, { next: 21, slot: 'HOME' }),
      played(12, 'QUARTERFINAL', 4, 5, { next: 21, slot: 'AWAY' }),
      played(13, 'QUARTERFINAL', 3, 6, { next: 22, slot: 'HOME' }),
      played(14, 'QUARTERFINAL', 2, 7, { next: 22, slot: 'AWAY' }),
    ],
  },
  { phase: 'SEMIFINAL', matches: [scheduled(21, 'SEMIFINAL', 1, 4), scheduled(22, 'SEMIFINAL', 3, 2)] },
]

describe('orderKnockoutPhases', () => {
  it('keeps the bracket order when match 1 and 2 feed the first semi-final (links)', () => {
    const ordered = orderKnockoutPhases(LINKED)
    expect(ordered.get('QUARTERFINAL')?.map((match) => match.id)).toEqual([11, 12, 13, 14])
    expect(ordered.get('SEMIFINAL')?.map((match) => match.id)).toEqual([21, 22])
  })

  it('draws a legacy (folded) bracket without links next to the semi-final each match fed', () => {
    // Old rule: QF listed by seed (1v8, 2v7, 3v6, 4v5) and SF1 = W1 v W4, SF2 = W2 v W3.
    const legacy: BracketPhase[] = [
      {
        phase: 'QUARTERFINAL',
        matches: [played(11, 'QUARTERFINAL', 1, 8), played(12, 'QUARTERFINAL', 2, 7), played(13, 'QUARTERFINAL', 3, 6), played(14, 'QUARTERFINAL', 4, 5)],
      },
      { phase: 'SEMIFINAL', matches: [scheduled(21, 'SEMIFINAL', 1, 4), scheduled(22, 'SEMIFINAL', 2, 3)] },
    ]
    expect(orderKnockoutPhases(legacy).get('QUARTERFINAL')?.map((match) => match.id)).toEqual([11, 14, 12, 13])
  })

  it('keeps id order for a round whose next round is not drawn yet', () => {
    const firstRound: BracketPhase[] = [LINKED[0] as BracketPhase]
    expect(orderKnockoutPhases(firstRound).get('QUARTERFINAL')?.map((match) => match.id)).toEqual([11, 12, 13, 14])
  })
})

describe('BracketView knockout drawing', () => {
  it('groups the quarter-finals in pairs next to the semi-final they feed', () => {
    render(
      <MemoryRouter>
        <BracketView phases={LINKED} />
      </MemoryRouter>,
    )
    const quarterFinals = within(screen.getByTestId('bracket-phase-QUARTERFINAL')).getAllByTestId('bracket-slot')
    expect(quarterFinals).toHaveLength(2)
    expect(within(quarterFinals[0] as HTMLElement).getByText('Equipo 1')).toBeInTheDocument()
    expect(within(quarterFinals[0] as HTMLElement).getByText('Equipo 4')).toBeInTheDocument()
    expect(within(quarterFinals[1] as HTMLElement).getByText('Equipo 3')).toBeInTheDocument()
    expect(within(quarterFinals[1] as HTMLElement).getByText('Equipo 2')).toBeInTheDocument()
    // The last column (semi-finals here) is one match per slot.
    expect(within(screen.getByTestId('bracket-phase-SEMIFINAL')).getAllByTestId('bracket-slot')).toHaveLength(2)
  })
})
