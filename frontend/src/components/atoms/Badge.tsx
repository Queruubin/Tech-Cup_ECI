import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'
import {
  JOIN_REQUEST_STATUS_LABELS,
  MATCH_STATUS_LABELS,
  REGISTRATION_STATUS_LABELS,
  ROLE_LABELS,
  TEAM_STATUS_LABELS,
  TOURNAMENT_STATUS_LABELS,
  USER_STATUS_LABELS,
} from '@/lib/labels'
import type {
  JoinRequestStatus,
  MatchStatus,
  RegistrationStatus,
  Role,
  TeamStatus,
  TournamentStatus,
  UserStatus,
} from '@/types/api'

export type BadgeTone = 'neutral' | 'success' | 'warning' | 'danger' | 'info' | 'accent'

export interface BadgeProps {
  tone?: BadgeTone
  children: ReactNode
  className?: string
  size?: 'sm' | 'md'
}

// success = green (a "good" state), danger = solid brand red, accent = brand red tint (live/emphasised),
// info = sky, neutral = stone. Tints and solids stay distinguishable from one another.
const TONE_CLASSES: Record<BadgeTone, string> = {
  neutral: 'bg-stone-100 text-stone-700 ring-stone-200',
  success: 'bg-green-50 text-green-700 ring-green-200',
  warning: 'bg-amber-50 text-amber-700 ring-amber-200',
  danger: 'bg-brand-600 text-white ring-brand-600',
  info: 'bg-sky-50 text-sky-700 ring-sky-200',
  accent: 'bg-brand-50 text-brand-700 ring-brand-200',
}

export function Badge({ tone = 'neutral', children, className, size = 'sm' }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center whitespace-nowrap rounded-full font-medium ring-1 ring-inset',
        size === 'sm' ? 'px-2 py-0.5 text-xs' : 'px-2.5 py-1 text-sm',
        TONE_CLASSES[tone],
        className,
      )}
    >
      {children}
    </span>
  )
}

// ---------------------------------------------------------------------------
// Status-aware badges. Each maps an API enum value to a tone + Spanish label.
// ---------------------------------------------------------------------------

type StatusValue =
  | { kind: 'tournament'; value: TournamentStatus }
  | { kind: 'registration'; value: RegistrationStatus }
  | { kind: 'match'; value: MatchStatus }
  | { kind: 'joinRequest'; value: JoinRequestStatus }
  | { kind: 'team'; value: TeamStatus }
  | { kind: 'user'; value: UserStatus }
  | { kind: 'role'; value: Role }

const TOURNAMENT_TONES: Record<TournamentStatus, BadgeTone> = {
  DRAFT: 'neutral',
  ACTIVE: 'success',
  IN_PROGRESS: 'accent',
  FINISHED: 'neutral',
}
const REGISTRATION_TONES: Record<RegistrationStatus, BadgeTone> = {
  UNDER_REVIEW: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  CANCELLED: 'neutral',
}
const MATCH_TONES: Record<MatchStatus, BadgeTone> = {
  SCHEDULED: 'info',
  PLAYED: 'success',
  CANCELLED: 'neutral',
}
const JOIN_REQUEST_TONES: Record<JoinRequestStatus, BadgeTone> = {
  PENDING: 'warning',
  ACCEPTED: 'success',
  REJECTED: 'danger',
  CANCELLED: 'neutral',
}
const ACTIVE_TONES: Record<TeamStatus, BadgeTone> = { ACTIVE: 'success', INACTIVE: 'neutral' }
const ROLE_TONES: Record<Role, BadgeTone> = {
  GUEST: 'neutral',
  PLAYER: 'info',
  CAPTAIN: 'success',
  ORGANIZER: 'warning',
  REFEREE: 'accent',
  ADMIN: 'danger',
}

function statusBadgeProps(status: StatusValue): { tone: BadgeTone; label: string } {
  switch (status.kind) {
    case 'tournament':
      return { tone: TOURNAMENT_TONES[status.value], label: TOURNAMENT_STATUS_LABELS[status.value] }
    case 'registration':
      return { tone: REGISTRATION_TONES[status.value], label: REGISTRATION_STATUS_LABELS[status.value] }
    case 'match':
      return { tone: MATCH_TONES[status.value], label: MATCH_STATUS_LABELS[status.value] }
    case 'joinRequest':
      return { tone: JOIN_REQUEST_TONES[status.value], label: JOIN_REQUEST_STATUS_LABELS[status.value] }
    case 'team':
      return { tone: ACTIVE_TONES[status.value], label: TEAM_STATUS_LABELS[status.value] }
    case 'user':
      return { tone: ACTIVE_TONES[status.value], label: USER_STATUS_LABELS[status.value] }
    case 'role':
      return { tone: ROLE_TONES[status.value], label: ROLE_LABELS[status.value] }
  }
}

export function StatusBadge(props: StatusValue & { className?: string; size?: 'sm' | 'md' }) {
  const { className, size, ...status } = props
  const { tone, label } = statusBadgeProps(status)
  return (
    <Badge tone={tone} className={className} size={size}>
      {label}
    </Badge>
  )
}
