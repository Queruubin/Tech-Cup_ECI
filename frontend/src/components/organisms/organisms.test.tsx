import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { BracketPhase, LineupPlayer, MatchPhase, MatchResponse } from '@/types/api'
import { BracketView, matchWinnerId } from './BracketView'
import { LineupPitch, arrangeLineup, formationRows } from './LineupPitch'

function makeMatch(overrides: Partial<MatchResponse> & { id: number; phase: MatchPhase }): MatchResponse {
  return {
    tournamentId: 1,
    roundNumber: 1,
    homeTeam: { id: 10, name: 'Leones FC', colors: 'Rojo y negro' },
    awayTeam: { id: 20, name: 'Águilas FC', colors: 'Azul' },
    venue: { id: 1, name: 'Cancha norte' },
    referee: { id: 99, fullName: 'Ana Ruiz' },
    scheduledAt: '2026-03-10T23:00:00Z',
    status: 'SCHEDULED',
    homeScore: null,
    awayScore: null,
    homePenalties: null,
    awayPenalties: null,
    cancelReason: null,
    walkoverWinnerTeamId: null,
    resultEditable: true,
    teamsEditable: true,
    reopenable: false,
    events: [],
    ...overrides,
  }
}

const PHASES: BracketPhase[] = [
  {
    phase: 'GROUP',
    matches: [
      makeMatch({ id: 1, phase: 'GROUP', roundNumber: 1, status: 'PLAYED', homeScore: 2, awayScore: 1 }),
      makeMatch({
        id: 2,
        phase: 'GROUP',
        roundNumber: 2,
        homeTeam: { id: 30, name: 'Tiburones FC', colors: 'Verde' },
        awayTeam: { id: 40, name: 'Cóndores FC', colors: 'Blanco' },
      }),
    ],
  },
  {
    phase: 'SEMIFINAL',
    matches: [makeMatch({ id: 3, phase: 'SEMIFINAL', status: 'PLAYED', homeScore: 0, awayScore: 3 })],
  },
  {
    phase: 'FINAL',
    matches: [
      makeMatch({
        id: 4,
        phase: 'FINAL',
        status: 'PLAYED',
        homeScore: 1,
        awayScore: 1,
        homePenalties: 4,
        awayPenalties: 2,
      }),
    ],
  },
]

describe('BracketView', () => {
  it('renders the group rounds and the knockout columns', () => {
    render(
      <MemoryRouter>
        <BracketView phases={PHASES} />
      </MemoryRouter>,
    )

    expect(screen.getByText('Fase de grupos')).toBeInTheDocument()
    expect(screen.getByText('Jornada 1')).toBeInTheDocument()
    expect(screen.getByText('Jornada 2')).toBeInTheDocument()
    expect(screen.getByText('Fase eliminatoria')).toBeInTheDocument()

    const semifinal = screen.getByTestId('bracket-phase-SEMIFINAL')
    expect(within(semifinal).getByText('Semifinal')).toBeInTheDocument()

    const final = screen.getByTestId('bracket-phase-FINAL')
    expect(within(final).getByText('(4)')).toBeInTheDocument()
    expect(within(final).getByText('(2)')).toBeInTheDocument()
    expect(screen.getByText('Tiburones FC')).toBeInTheDocument()
  })

  it('links every match box when a href builder is given', () => {
    render(
      <MemoryRouter>
        <BracketView phases={PHASES} matchHref={(match) => `/matches/${match.id}`} />
      </MemoryRouter>,
    )
    const links = screen.getAllByRole('link')
    expect(links).toHaveLength(4)
    expect(links[0]).toHaveAttribute('href', '/matches/1')
  })

  it('shows an empty state when there are no matches', () => {
    render(
      <MemoryRouter>
        <BracketView phases={[]} />
      </MemoryRouter>,
    )
    expect(screen.getByText('Llaves no disponibles')).toBeInTheDocument()
  })

  it('resolves the winner by score and then by penalties', () => {
    const decided = makeMatch({ id: 1, phase: 'GROUP', status: 'PLAYED', homeScore: 2, awayScore: 1 })
    const onPenalties = makeMatch({
      id: 4,
      phase: 'FINAL',
      status: 'PLAYED',
      homeScore: 1,
      awayScore: 1,
      homePenalties: 2,
      awayPenalties: 4,
    })
    expect(matchWinnerId(decided)).toBe(10)
    expect(matchWinnerId(onPenalties)).toBe(20)
    expect(matchWinnerId(makeMatch({ id: 9, phase: 'GROUP' }))).toBeNull()
  })

  it('treats the walkover winner of a cancelled knockout match as the winner', () => {
    const walkover = makeMatch({ id: 5, phase: 'SEMIFINAL', status: 'CANCELLED', cancelReason: 'NO_SHOW', walkoverWinnerTeamId: 20 })
    expect(matchWinnerId(walkover)).toBe(20)
    expect(matchWinnerId(makeMatch({ id: 6, phase: 'GROUP', status: 'CANCELLED', cancelReason: 'NO_SHOW' }))).toBeNull()
  })
})

const STARTERS: LineupPlayer[] = [
  { userId: 1, fullName: 'Camilo Rivas', position: 'GOALKEEPER', jerseyNumber: 1 },
  { userId: 2, fullName: 'Daniel Soto', position: 'DEFENDER', jerseyNumber: 2 },
  { userId: 3, fullName: 'Esteban Luna', position: 'DEFENDER', jerseyNumber: 3 },
  { userId: 4, fullName: 'Felipe Mora', position: 'MIDFIELDER', jerseyNumber: 4 },
  { userId: 5, fullName: 'Gabriel Nieto', position: 'MIDFIELDER', jerseyNumber: 5 },
  { userId: 6, fullName: 'Hugo Peña', position: 'MIDFIELDER', jerseyNumber: 6 },
  { userId: 7, fullName: 'Iván Quintero', position: 'FORWARD', jerseyNumber: 7 },
]

describe('LineupPitch', () => {
  it('renders every starter on the pitch with the formation label', () => {
    render(<LineupPitch formation="F_2_3_1" starters={STARTERS} />)

    expect(screen.getByRole('img', { name: 'Alineación 2-3-1' })).toBeInTheDocument()
    expect(screen.getByText('Formación 2-3-1')).toBeInTheDocument()
    for (const player of STARTERS) {
      expect(screen.getByText(String(player.jerseyNumber))).toBeInTheDocument()
      expect(screen.getByText(player.fullName.split(' ')[0] as string)).toBeInTheDocument()
    }
  })

  it('marks the empty slots when there are fewer starters than the formation needs', () => {
    render(<LineupPitch formation="F_3_2_1" starters={STARTERS.slice(0, 3)} />)
    expect(screen.getAllByText('Libre')).toHaveLength(4)
  })

  it('derives the rows from the formation name', () => {
    expect(formationRows('F_2_3_1')).toEqual([2, 3, 1])
    expect(formationRows('F_4_1_1')).toEqual([4, 1, 1])
  })

  it('places the goalkeeper apart from the outfield rows', () => {
    const { goalkeeper, rows } = arrangeLineup('F_2_3_1', STARTERS)
    expect(goalkeeper?.userId).toBe(1)
    expect(rows.map((row) => row.length)).toEqual([2, 3, 1])
    expect(rows.flat().some((player) => player?.position === 'GOALKEEPER')).toBe(false)
  })
})
