import { Link } from 'react-router'
import { Table, type Column } from '@/components/molecules/Table'
import { cn } from '@/lib/cn'
import type { StandingRow } from '@/types/api'

export interface StandingsTableProps {
  rows: StandingRow[]
  /** Highlights the row of this team (e.g. the viewer's team). */
  highlightTeamId?: number | null
  /** Number of leading rows to mark as qualified (knockout cut line). */
  qualifiedCount?: number
  compact?: boolean
  emptyMessage?: string
}

export function StandingsTable({
  rows,
  highlightTeamId,
  qualifiedCount = 0,
  compact = false,
  emptyMessage = 'Aún no hay partidos jugados.',
}: StandingsTableProps) {
  const columns: Column<StandingRow>[] = [
    { key: 'pos', header: '#', align: 'center', className: 'w-10', cell: (row) => row.position },
    {
      key: 'team',
      header: 'Equipo',
      cell: (row) => (
        <Link to={`/teams/${row.teamId}`} className="font-medium text-ink hover:text-brand-700">
          {row.teamName}
        </Link>
      ),
    },
    { key: 'played', header: 'PJ', align: 'center', cell: (row) => row.played },
    { key: 'won', header: 'PG', align: 'center', cell: (row) => row.won, hideOnMobile: compact },
    { key: 'drawn', header: 'PE', align: 'center', cell: (row) => row.drawn, hideOnMobile: compact },
    { key: 'lost', header: 'PP', align: 'center', cell: (row) => row.lost, hideOnMobile: compact },
    { key: 'gf', header: 'GF', align: 'center', cell: (row) => row.goalsFor, hideOnMobile: true },
    { key: 'ga', header: 'GC', align: 'center', cell: (row) => row.goalsAgainst, hideOnMobile: true },
    {
      key: 'gd',
      header: 'DG',
      align: 'center',
      cell: (row) => (row.goalDifference > 0 ? `+${row.goalDifference}` : row.goalDifference),
    },
    {
      key: 'pts',
      header: 'Pts',
      align: 'center',
      className: 'font-semibold',
      cell: (row) => <span className="font-semibold text-ink">{row.points}</span>,
    },
  ]

  return (
    <Table
      columns={columns}
      rows={rows}
      rowKey={(row) => row.teamId}
      dense={compact}
      empty={emptyMessage}
      rowClassName={(row) =>
        cn(
          row.teamId === highlightTeamId && 'bg-brand-50/70',
          qualifiedCount > 0 && row.position === qualifiedCount && 'border-b-2 border-b-brand-300',
        )
      }
    />
  )
}
