import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Avatar } from '@/components/atoms/Avatar'
import { Badge } from '@/components/atoms/Badge'
import { EmptyState } from '@/components/molecules/EmptyState'
import { ACADEMIC_PROGRAM_LABELS, POSITION_LABELS } from '@/lib/labels'
import type { TeamMember } from '@/types/api'

export interface TeamRosterProps {
  members: TeamMember[]
  captainId?: number | null
  /** Renders per-member actions (e.g. a remove button). Presentational only. */
  renderActions?: (member: TeamMember) => ReactNode
  /** When given, each member's name links there (e.g. staff open the user's profile). */
  memberHref?: (member: TeamMember) => string
}

export function TeamRoster({ members, captainId, renderActions, memberHref }: TeamRosterProps) {
  if (members.length === 0) {
    return <EmptyState title="Sin integrantes" description="Este equipo todavía no tiene jugadores vinculados." />
  }
  return (
    <ul className="divide-y divide-stone-100 rounded-2xl border border-stone-200 bg-white">
      {members.map((member) => (
        <li key={member.userId} className="flex items-center gap-3 px-4 py-3">
          <Avatar name={member.fullName} photoFileId={member.photoFileId} size="md" />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              {memberHref ? (
                <Link
                  to={memberHref(member)}
                  className="truncate text-sm font-medium text-ink underline-offset-2 hover:text-brand-700 hover:underline"
                >
                  {member.fullName}
                </Link>
              ) : (
                <p className="truncate text-sm font-medium text-ink">{member.fullName}</p>
              )}
              {member.userId === captainId && <Badge tone="success">Capitán</Badge>}
            </div>
            <p className="truncate text-xs text-stone-500">
              {POSITION_LABELS[member.position]} · {ACADEMIC_PROGRAM_LABELS[member.academicProgram]}
            </p>
          </div>
          <span
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-ink text-sm font-semibold tabular-nums text-white"
            aria-label={`Dorsal ${member.jerseyNumber}`}
          >
            {member.jerseyNumber}
          </span>
          {renderActions && <div className="shrink-0">{renderActions(member)}</div>}
        </li>
      ))}
    </ul>
  )
}
