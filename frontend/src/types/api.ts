/**
 * TypeScript mirror of the backend REST contract (docs/ARCHITECTURE.md section 3.5).
 * Enums are modelled as string-literal unions backed by `as const` arrays so they can be
 * iterated for selects and labels without TypeScript `enum` runtime code.
 */

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

export const ROLES = ['GUEST', 'PLAYER', 'CAPTAIN', 'ORGANIZER', 'REFEREE', 'ADMIN'] as const
export type Role = (typeof ROLES)[number]

export const SCHOOL_RELATIONS = ['STUDENT', 'PROFESSOR', 'ADMINISTRATIVE', 'GRADUATE', 'FAMILY'] as const
export type SchoolRelation = (typeof SCHOOL_RELATIONS)[number]

export const ACADEMIC_PROGRAMS = [
  'SYSTEMS_ENGINEERING',
  'AI_ENGINEERING',
  'CYBERSECURITY_ENGINEERING',
  'STATISTICS_ENGINEERING',
  'OTHER',
] as const
export type AcademicProgram = (typeof ACADEMIC_PROGRAMS)[number]

export const DOCUMENT_TYPES = ['CC', 'TI', 'CE', 'PASSPORT'] as const
export type DocumentType = (typeof DOCUMENT_TYPES)[number]

export const USER_STATUSES = ['ACTIVE', 'INACTIVE'] as const
export type UserStatus = (typeof USER_STATUSES)[number]

export const POSITIONS = ['GOALKEEPER', 'DEFENDER', 'MIDFIELDER', 'FORWARD'] as const
export type Position = (typeof POSITIONS)[number]

export const TEAM_STATUSES = ['ACTIVE', 'INACTIVE'] as const
export type TeamStatus = (typeof TEAM_STATUSES)[number]

export const JOIN_REQUEST_STATUSES = ['PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED'] as const
export type JoinRequestStatus = (typeof JOIN_REQUEST_STATUSES)[number]

export const TOURNAMENT_STATUSES = ['DRAFT', 'ACTIVE', 'IN_PROGRESS', 'FINISHED'] as const
export type TournamentStatus = (typeof TOURNAMENT_STATUSES)[number]

export const REGISTRATION_STATUSES = ['UNDER_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED'] as const
export type RegistrationStatus = (typeof REGISTRATION_STATUSES)[number]

export const MATCH_PHASES = ['GROUP', 'QUARTERFINAL', 'SEMIFINAL', 'FINAL'] as const
export type MatchPhase = (typeof MATCH_PHASES)[number]

export const MATCH_STATUSES = ['SCHEDULED', 'PLAYED', 'CANCELLED'] as const
export type MatchStatus = (typeof MATCH_STATUSES)[number]

export const CANCEL_REASONS = ['DISQUALIFIED', 'NO_SHOW'] as const
export type CancelReason = (typeof CANCEL_REASONS)[number]

export const EVENT_TYPES = ['GOAL', 'YELLOW_CARD', 'RED_CARD'] as const
export type EventType = (typeof EVENT_TYPES)[number]

export const FORMATIONS = ['F_3_2_1', 'F_2_3_1', 'F_4_1_1', 'F_1_3_2'] as const
export type Formation = (typeof FORMATIONS)[number]

export const INITIAL_ROLES = ['PLAYER', 'GUEST'] as const
export type InitialRole = (typeof INITIAL_ROLES)[number]

/** Mirrors the backend `AuditAction` enum exactly; the audit filter sends these values verbatim. */
export const AUDIT_ACTIONS = [
  'USER_REGISTERED',
  'LOGIN',
  'LOGIN_FAILED',
  'LOGOUT',
  'PASSWORD_CHANGED',
  'PASSWORD_RESET_BY_ADMIN',
  'ROLE_ASSIGNED',
  'ROLE_REMOVED',
  'REFEREE_CREATED',
  'USER_INACTIVATED',
  'USER_UPDATED',
  'PROFILE_CREATED',
  'PROFILE_UPDATED',
  'JOIN_REQUEST_CREATED',
  'JOIN_REQUEST_CANCELLED',
  'JOIN_REQUEST_ACCEPTED',
  'JOIN_REQUEST_REJECTED',
  'TEAM_CREATED',
  'TEAM_UPDATED',
  'TEAM_MEMBER_REMOVED',
  'TEAM_INACTIVATED',
  'TOURNAMENT_CREATED',
  'TOURNAMENT_UPDATED',
  'TOURNAMENT_DELETED',
  'TOURNAMENT_ACTIVATED',
  'TOURNAMENT_STARTED',
  'TOURNAMENT_FINISHED',
  'RULEBOOK_UPLOADED',
  'VENUE_CREATED',
  'VENUE_DELETED',
  'REGISTRATION_CREATED',
  'REGISTRATION_APPROVED',
  'REGISTRATION_REJECTED',
  'REGISTRATION_CANCELLED',
  'MATCHES_GENERATED',
  'MATCH_UPDATED',
  'MATCH_CANCELLED',
  'MATCH_RESULT_RECORDED',
  'MATCH_RESULT_CORRECTED',
  'LINEUP_SAVED',
] as const
export type KnownAuditAction = (typeof AUDIT_ACTIONS)[number]
/** Known audit actions. The backend may emit others; treat as an open string. */
export type AuditAction = KnownAuditAction | (string & {})

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

export interface ApiErrorDetail {
  field: string
  message: string
}

export interface ApiErrorBody {
  timestamp?: string
  status: number
  error: string
  message: string
  path?: string
  details?: ApiErrorDetail[]
}

// ---------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------

/**
 * Personal fields (`email`, `birthDate`, `documentType`, `documentNumber`) are `null` when the
 * viewer is neither the same user nor an ADMIN; organizer listings also omit document/birth data.
 */
export interface UserResponse {
  id: number
  fullName: string
  email: string | null
  schoolRelation: SchoolRelation
  academicProgram: AcademicProgram
  semester: number | null
  status: UserStatus
  birthDate: string | null
  documentType: DocumentType | null
  documentNumber: string | null
  roles: Role[]
  hasProfile: boolean
  teamId?: number | null
}

export interface RegisterRequest {
  fullName: string
  email: string
  password: string
  schoolRelation: SchoolRelation
  academicProgram: AcademicProgram
  semester?: number | null
  birthDate: string
  documentType: DocumentType
  documentNumber: string
  initialRole: InitialRole
}

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  token: string
  expiresAt: string
  user: UserResponse
}

export interface UpdateUserRequest {
  fullName: string
  schoolRelation: SchoolRelation
  academicProgram: AcademicProgram
  semester?: number | null
}

export interface AssignRoleRequest {
  role: Role
}

/** `POST /auth/password` (self-service). Password rule: 8–72 chars, at least one letter and one digit. */
export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

/** `POST /admin/users/{id}/password` (ADMIN). Same password rule as `ChangePasswordRequest`. */
export interface ResetPasswordRequest {
  newPassword: string
}

export interface CreateRefereeRequest {
  fullName: string
  email: string
  password: string
  birthDate: string
  documentType: DocumentType
  documentNumber: string
}

export interface AuditLogResponse {
  id: number
  actorUserId: number | null
  actorName?: string | null
  action: AuditAction
  entityType: string
  entityId: string | number | null
  details: Record<string, unknown> | null
  createdAt: string
}

// ---------------------------------------------------------------------------
// Players
// ---------------------------------------------------------------------------

export interface PlayerProfileResponse {
  userId: number
  fullName: string
  position: Position
  jerseyNumber: number
  photoFileId: string | null
  teamId?: number | null
  teamName?: string | null
}

export interface UpsertProfileRequest {
  position: Position
  jerseyNumber: number
}

export interface JoinRequestResponse {
  id: number
  teamId: number
  teamName: string
  playerId: number
  playerName: string
  position: Position
  jerseyNumber: number
  status: JoinRequestStatus
  message: string | null
  createdAt: string
}

export interface CreateJoinRequest {
  message?: string
}

// ---------------------------------------------------------------------------
// Teams
// ---------------------------------------------------------------------------

export interface UserSummary {
  id: number
  fullName: string
}

export interface TeamMember {
  userId: number
  fullName: string
  position: Position
  jerseyNumber: number
  academicProgram: AcademicProgram
  photoFileId: string | null
}

export interface TeamResponse {
  id: number
  name: string
  colors: string
  status: TeamStatus
  captain: UserSummary
  members: TeamMember[]
  memberCount: number
  locked: boolean
}

export interface CreateTeamRequest {
  name: string
  colors: string
}

export interface UpdateTeamRequest {
  name?: string
  colors?: string
}

export interface EligibilityResponse {
  eligible: boolean
  problems: string[]
}

// ---------------------------------------------------------------------------
// Tournaments
// ---------------------------------------------------------------------------

export interface VenueResponse {
  id: number
  name: string
  description: string
  imageFileId: string | null
}

export interface TournamentResponse {
  id: number
  name: string
  startDate: string
  endDate: string
  registrationDeadline: string
  maxTeams: number
  fee: number
  status: TournamentStatus
  rulebookFileId: string | null
  venues: VenueResponse[]
  approvedTeams: number
}

export interface CreateTournamentRequest {
  name: string
  startDate: string
  endDate: string
  registrationDeadline: string
  maxTeams: number
  fee: number
}

export type UpdateTournamentRequest = Partial<CreateTournamentRequest>

export interface RegistrationResponse {
  id: number
  tournamentId: number
  teamId: number
  teamName: string
  /** `null` when the viewer may not see the receipt (e.g. non-captain in `HomeResponse`). */
  receiptFileId: string | null
  status: RegistrationStatus
  reviewNote: string | null
  createdAt: string
  reviewedAt: string | null
}

export interface ReviewRegistrationRequest {
  note?: string
}

// ---------------------------------------------------------------------------
// Competition
// ---------------------------------------------------------------------------

export interface MatchTeam {
  id: number
  name: string
  colors: string
}

export interface MatchVenue {
  id: number
  name: string
}

export interface MatchEvent {
  id: number
  teamId: number
  playerId: number
  playerName: string
  type: EventType
  minute: number | null
}

export interface MatchResponse {
  id: number
  tournamentId: number
  phase: MatchPhase
  roundNumber: number
  homeTeam: MatchTeam
  awayTeam: MatchTeam
  venue: MatchVenue | null
  referee: UserSummary | null
  scheduledAt: string | null
  status: MatchStatus
  homeScore: number | null
  awayScore: number | null
  homePenalties: number | null
  awayPenalties: number | null
  cancelReason: CancelReason | null
  /** Team that advances when a knockout match is cancelled (walkover); `null` otherwise. */
  walkoverWinnerTeamId: number | null
  /** True when `POST /matches/{id}/result` is currently allowed (first result or a correction). */
  resultEditable: boolean
  events: MatchEvent[]
}

export interface UpdateMatchRequest {
  scheduledAt?: string
  venueId?: number
  refereeId?: number
}

export interface MatchEventRequest {
  teamId: number
  playerId: number
  type: EventType
  minute?: number
}

export interface MatchResultRequest {
  homeScore: number
  awayScore: number
  homePenalties?: number
  awayPenalties?: number
  events: MatchEventRequest[]
}

export interface LineupPlayer {
  userId: number
  fullName: string
  position: Position
  jerseyNumber: number
}

export interface LineupResponse {
  matchId: number
  teamId: number
  formation: Formation
  starters: LineupPlayer[]
  substitutes: LineupPlayer[]
}

export interface UpsertLineupRequest {
  formation: Formation
  starterIds: number[]
}

export interface StandingRow {
  position: number
  teamId: number
  teamName: string
  played: number
  won: number
  drawn: number
  lost: number
  goalsFor: number
  goalsAgainst: number
  goalDifference: number
  points: number
}

export interface BracketPhase {
  phase: MatchPhase
  matches: MatchResponse[]
}

export interface BracketResponse {
  phases: BracketPhase[]
}

export interface TopScorer {
  playerId: number
  playerName: string
  teamId: number
  teamName: string
  goals: number
}

export interface SanctionedPlayer {
  userId: number
  fullName: string
  teamId: number
  teamName: string
  reason: string
}

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

export interface HomeResponse {
  tournament: TournamentResponse | null
  myTeam: TeamResponse | null
  myRegistration: RegistrationResponse | null
  upcomingMatches: MatchResponse[]
  standingsTop: StandingRow[]
}
