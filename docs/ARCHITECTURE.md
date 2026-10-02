# TechCup Fútbol — Architecture

Platform for managing the semester football tournament of the Escuela Colombiana de Ingeniería
(Systems, AI, Cybersecurity and Statistics programs). Source requirements: `docs/spec-extracted.md`.

## 1. Decisions

| Topic | Decision | Why |
|---|---|---|
| Topology | **Modular monolith** (one Spring Boot app), NOT microservices | ~100 users, one tournament per semester. The five "functional domains" of the spec become five packages with strict layering. |
| "Orchestrator" | Folded into the monolith: JWT filter, global exception handler, `/api/home` | It is what an API gateway does, minus the network hop. |
| Backend | Java 17, Spring Boot 3.x, Maven (`artifactId: techcup`), Spring Security + JWT (jjwt), Spring Data JPA, Flyway, Bean Validation | Spec requires Spring Boot layered + REST + Maven. |
| Relational DB | PostgreSQL 16 | Spec requirement. |
| Binary files | MongoDB 7 via **GridFS** (profile photos, venue images, payment receipts, rulebook PDF) | Spec lists "Almacenamiento de imágenes: MongoDB". Only binaries live there; every reference is a `file_id` string column in Postgres. |
| Frontend | React 18 + TypeScript, Vite, Tailwind CSS v4, Zustand (session/global), React Router, pnpm | User choice. |
| Local infra | `docker-compose.yml` with postgres + mongo | Dev machine has Docker but no local psql. |
| Design patterns | Hexagonal-light per module (ports/adapters), Strategy (bracket phase generation), State (tournament / registration / match status transitions), Builder (JPA entities via Lombok), Factory (match generation), Observer-ish audit via a dedicated `AuditService` | Spec: "No olvide el uso de patrones de diseño". |
| Languages | Code, comments, docs: English. **UI copy: neutral Spanish** (end users are Spanish-speaking students). | Target audience. |

## 2. Repository layout

```
tech-cup/
  backend/              Spring Boot monolith (Maven)
  frontend/             React + Vite (pnpm)
  docs/                 this file, extracted spec
  docker-compose.yml    postgres + mongo (+ optional backend/frontend profiles)
  README.md
```

## 3. Backend

### 3.1 Package layout (`edu.escuelaing.techcup`)

```
TechcupApplication.java
shared/
  config/         CorsConfig, OpenApiConfig, JacksonConfig, AppProperties, TraceIdFilter
  security/       SecurityConfig, JwtService, JwtAuthenticationFilter, CurrentUser (resolver), Roles
  exception/      GlobalExceptionHandler, ApiError, NotFoundException, ConflictException,
                  ForbiddenOperationException, BusinessRuleException
  audit/          AuditLog (entity), AuditAction (enum), AuditService, AuditRepository, AuditController (admin)
  storage/        FileStorage (port), GridFsFileStorage (adapter), StoredFile, FileController
  home/           HomeController, HomeService
identity/         register, login, logout, roles, inactivate
players/          user profile update, sport profile, join requests, invitations, free-agent search
teams/            teams + members
tournaments/      tournaments, venues, rulebook, registrations (payments)
competition/      matches, lineups, results/events, standings, bracket, stats, referee queries
```

Every domain module uses the same four layers:

```
<module>/
  api/            @RestController + request/response DTOs (records)
  application/    @Service use cases, transactional, enforce business rules, call AuditService
  domain/         JPA entities, enums, pure domain rules (no Spring web imports)
  infrastructure/ Spring Data repositories, external adapters
```

Every public mutating method of an `application/*Service` is `@Transactional`, so a use case that
fails part-way (a result plus its events plus the knockout propagation, generate/advance/undo-phase,
an invitation acceptance) rolls back entirely; audit rows join the same transaction. The one
exception is `AuthService.login`: it is deliberately not transactional (each repository call and
audit write gets its own short transaction), so a burst of failed logins cannot hold one pooled
connection per attempt while the detached `LOGIN_FAILED` audit asks for a second one.
Tournament-wide use cases serialise on the tournament row (`TournamentService.requireTournamentForUpdate`,
`SELECT ... FOR UPDATE`): generate, advance, undo-phase, finish, registration approval, and every
match mutation (update, cancel, result, reopen), which locks the tournament before reading the match.

Rule of thumb: controllers never touch repositories; services never build HTTP responses;
domain never imports `org.springframework.web`. Cross-module calls go through the other
module's `application` service (never its repository).

JPA entities double as domain entities (pragmatic hexagonal). Persistence-agnostic domain
models would double the code for a semester project with no benefit.

### 3.2 Security

- Stateless JWT (HS256), claims: `sub` = user id, `email`, `roles`. Expiry from `app.jwt.expiration-minutes` (default 480).
- `JwtAuthenticationFilter` validates signature + expiry on every request, loads `AppUser` into
  `SecurityContext`. Inactive users are rejected with 401.
- Method security with `@PreAuthorize("hasRole('ORGANIZER')")` etc. Roles: `GUEST, PLAYER, CAPTAIN, ORGANIZER, REFEREE, ADMIN`.
  A user can hold several roles (`user_roles` table). `ADMIN` implies everything (role hierarchy).
- Passwords: BCrypt. Every request that sets a password (register, referee creation, self-service change, admin reset) requires 8–72 characters with at least one uppercase letter and one digit (`PasswordRules`); login does not re-validate, so older passwords keep working. `ADMIN_PASSWORD` is not validated against this rule.
- E-mail: any address may register; there is no domain rule for any school relation or role.
- Player age: the PLAYER role is limited to ages `app.player.min-age`..`app.player.max-age`
  (env `APP_PLAYER_MIN_AGE` / `APP_PLAYER_MAX_AGE`, default 5..100, inclusive, full years computed
  with the application `Clock` in `app.time-zone`). Enforced by `identity/application/PlayerAgePolicy`.
- Roles are read from the database on every request (the JWT `roles` claim is informational), so a
  role granted during a request (e.g. CAPTAIN on team creation) applies from the next request on.
- Admin is created at startup by `identity/infrastructure/AdminBootstrap` when no `ADMIN` user exists, from `ADMIN_EMAIL` / `ADMIN_PASSWORD` (dev defaults `admin@escuelaing.edu.co` / `Admin123*`, logged with a warning). The `prod` profile (`ProductionGuard`) refuses to start with the dev admin password, the dev (or a shorter than 32-byte) JWT secret, the dev PostgreSQL password (`DB_PASSWORD=techcup`) or the dev MongoDB credentials (`MONGO_URI` containing `techcup:techcup@`), and logs a WARNING (without failing) when `server.address` is not a loopback address (or is unset) and when `CORS_ORIGINS` contains `localhost`.
- Login is throttled: 5 failures per e-mail in 15 minutes → 429 (per e-mail only, not per client IP, so users behind a shared proxy or NAT never block each other), audited as `LOGIN_FAILED`. Duplicate e-mail/document at registration share one generic message (no account enumeration). The counters are in memory (`LoginAttemptService`); expired keys are purged every 15 minutes (`@Scheduled`, `shared/config/SchedulingConfig`) and also on a recorded failure once more than 10 000 keys are tracked (at most once a minute), so random e-mails cannot grow it without bound.
- Client IP behind the reverse proxy: the `prod` profile uses `server.forward-headers-strategy: native` (Tomcat `RemoteIpValve`). It trusts `X-Forwarded-For` / `X-Forwarded-Proto` only from internal proxies (Tomcat's default `internal-proxies`: 127/8, 10/8, 172.16/12, 192.168/16, 169.254/16, IPv6 loopback/ULA, i.e. the host nginx and the Docker network), takes the right-most untrusted `X-Forwarded-For` entry as the client address, and ignores the RFC 7239 `Forwarded` header. `framework` (Spring's `ForwardedHeaderFilter`) must not be used: it believes `Forwarded` / `X-Forwarded-*` from any client, which would let an attacker choose its own IP and scheme.
- Public (no token): `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/files/{id}`? → **No**, files require auth. Public read-only: `GET /api/tournaments/**` (list, detail, standings, bracket, stats, matches) so anyone can see the tournament. Everything else authenticated.
- Logout: audited, and the presented token's `jti` goes into an in-memory `TokenDenylist` until its expiry; the filter rejects denylisted tokens with 401.
- Personal data (`email`, `birthDate`, `documentType`, `documentNumber`) is returned only to the user themself or an ADMIN (`UserService.toResponse(user, viewer)`); organizers get the e-mail in `GET /admin/users` and `GET /organizer/referees` only. `GET /players` and `GET /players/{id}/profile` require CAPTAIN or ORGANIZER.
- Files: uploads are validated by magic bytes and stored with `kind` + owner metadata in GridFS; `GET /files/{id}` serves receipts only to ORGANIZER/ADMIN or the registering captain (`FileAccessPolicy` port implemented in `tournaments/infrastructure`), adds `X-Content-Type-Options: nosniff` and sends PDFs as attachments. Replaced/orphaned binaries are deleted after the DB transaction commits (`FileDeletionScheduler`), and a binary stored by a transaction that then rolls back (receipt, rulebook, venue image, photo) is deleted by `GridFsFileStorage` in an `afterCompletion(STATUS_ROLLED_BACK)` synchronization.

### 3.3 Data model (PostgreSQL, Flyway `V1__schema.sql` + `V3__match_walkover.sql` + `V4__join_request_direction.sql` + `V5__pending_join_request_uniqueness.sql`)

All ids are `BIGSERIAL`. Timestamps are `TIMESTAMPTZ`. Enums are `VARCHAR` with CHECK constraints
(mapped as `@Enumerated(EnumType.STRING)`).

```
users
  id, full_name, email UNIQUE, password_hash,
  school_relation  STUDENT|PROFESSOR|ADMINISTRATIVE|GRADUATE|FAMILY,
  academic_program SYSTEMS_ENGINEERING|AI_ENGINEERING|CYBERSECURITY_ENGINEERING|STATISTICS_ENGINEERING|OTHER,
  semester INT NULL, status ACTIVE|INACTIVE, birth_date DATE,
  document_type CC|TI|CE|PASSPORT, document_number, created_at, updated_at
  UNIQUE(document_type, document_number)

user_roles (user_id FK, role) PK(user_id, role)

audit_logs
  id, actor_user_id NULL FK, action VARCHAR, entity_type, entity_id, details JSONB NULL, created_at

player_profiles
  user_id PK FK, position GOALKEEPER|DEFENDER|MIDFIELDER|FORWARD, jersey_number INT (1..99),
  photo_file_id VARCHAR NULL, created_at, updated_at

teams
  id, name UNIQUE, colors VARCHAR, captain_user_id FK, status ACTIVE|INACTIVE, created_at, updated_at

team_members (team_id FK, user_id FK, joined_at) PK(team_id, user_id)
  -- "a player cannot be in two teams": service enforces user_id appears in at most one ACTIVE team.
  -- captain is also a member.

join_requests
  id, team_id FK, player_user_id FK, status PENDING|ACCEPTED|REJECTED|CANCELLED, message NULL,
  direction REQUEST|INVITATION (default REQUEST), created_at, resolved_at NULL, version
  -- REQUEST: the player asks the team. INVITATION: the team (captain) invites the player.
  -- service checks, and V5 partial unique indexes guarantee against double submits:
  --   ux_join_requests_pending_request    UNIQUE(player_user_id)          WHERE status='PENDING' AND direction='REQUEST'
  --   ux_join_requests_pending_invitation UNIQUE(team_id, player_user_id) WHERE status='PENDING' AND direction='INVITATION'
  -- i.e. at most ONE PENDING REQUEST per player (invitations do not count) and at most ONE
  -- PENDING INVITATION per (team, player). A violation (lost race) is answered with the same 409
  -- message as the service check (services flush the insert and translate the constraint;
  -- GlobalExceptionHandler maps the constraint names as a fallback). V5 first cancels any
  -- pre-existing duplicate PENDING rows, keeping the newest.

tournaments
  id, name, start_date DATE, end_date DATE, registration_deadline DATE, max_teams INT, fee NUMERIC(12,2),
  status DRAFT|ACTIVE|IN_PROGRESS|FINISHED, rulebook_file_id NULL, created_by FK, created_at, updated_at

venues
  id, tournament_id FK, name, description, image_file_id NULL

registrations
  id, tournament_id FK, team_id FK, receipt_file_id, status UNDER_REVIEW|APPROVED|REJECTED|CANCELLED,
  review_note NULL, reviewed_by NULL FK, created_at, reviewed_at NULL
  UNIQUE(tournament_id, team_id)

matches
  id, tournament_id FK, phase GROUP|QUARTERFINAL|SEMIFINAL|FINAL, round_number INT,
  home_team_id FK, away_team_id FK, venue_id NULL FK, referee_user_id NULL FK,
  scheduled_at TIMESTAMPTZ NULL, status SCHEDULED|PLAYED|CANCELLED,
  home_score INT NULL, away_score INT NULL, home_penalties INT NULL, away_penalties INT NULL,
  cancel_reason DISQUALIFIED|NO_SHOW NULL, created_at, updated_at

match_events
  id, match_id FK, team_id FK, player_user_id FK, type GOAL|YELLOW_CARD|RED_CARD, minute INT NULL

lineups
  id, match_id FK, team_id FK, formation F_3_2_1|F_2_3_1|F_4_1_1|F_1_3_2 (default F_2_3_1), updated_at
  UNIQUE(match_id, team_id)

lineup_players (lineup_id FK, player_user_id FK, starter BOOLEAN) PK(lineup_id, player_user_id)
```

### 3.4 Business rules (where enforced)

**identity**
- Register: the form no longer asks for the school relation (nor semester) or the role: `schoolRelation` is optional (null until an ADMIN sets it via `PATCH /users/{id}`) and `initialRole` defaults to PLAYER (GUEST still accepted from the API); `semester` required iff STUDENT; any e-mail; PLAYER only within the age range (409 "Para ser jugador la edad debe estar entre 5 y 100 años.", built from the configured bounds); status ACTIVE. Audit `USER_REGISTERED`.
- Login → JWT. Audit `LOGIN`. Logout audit `LOGOUT`.
- Admin assigns/removes/lists any role (except cannot remove own ADMIN). Assigning PLAYER re-checks the age range; CAPTAIN requires PLAYER and cannot be removed by hand while the user captains an ACTIVE team; likewise PLAYER cannot be removed while the user captains or belongs to an ACTIVE team (409). PLAYER and CAPTAIN cannot be removed while the user is locked by a tournament (member of a locked team, 409).
- CAPTAIN is not appointed by anyone: `RoleService.grantCaptainForNewTeam(userId, teamId)` grants it to whoever creates a team (same transaction, audit `ROLE_ASSIGNED` `{role: CAPTAIN, reason: TEAM_CREATED, teamId}`, actor = creator) and `RoleService.revokeCaptainForClosedTeam(actorId, userId, teamId)` removes it when the team is inactivated (audit `ROLE_REMOVED`, reason `TEAM_INACTIVATED`, actor = who inactivated). Dependency is one-way: `teams` calls `identity`, never the reverse.
- Organizer creates `REFEREE` users (no domain or age rule); organizer never changes roles.
- Inactivate user (admin): forbidden if user is a member of a team with an APPROVED registration in a tournament with status ACTIVE or IN_PROGRESS. Audit `USER_INACTIVATED`.

**players**
- Update basic info (self or admin): fullName, academicProgram, semester (semester only for STUDENT). `schoolRelation` only by an ADMIN: a non-admin sending a different value gets 403 "Solo un administrador puede cambiar la relación con la Escuela.". While the user is locked by a tournament nobody, ADMIN included, may change `schoolRelation` or `academicProgram` (409; they decide team eligibility); name and semester stay editable. Email/password immutable. Audit `USER_UPDATED`.
- Sport profile create/update (PLAYER): position, jerseyNumber, photo. Creation re-checks the player age range (legacy accounts). Update forbidden while member of an ACTIVE team. Never deletable. Audit `PROFILE_CREATED|PROFILE_UPDATED`.
- Join request (`direction = REQUEST`; PLAYER with profile, not in a team): at most one PENDING request at a time; team must be ACTIVE, not locked (roster frozen, 409) and have < 12 members. Player may cancel while PENDING.
- Captain accepts/rejects. Accept → locks the player row, validates jersey uniqueness within team, team size < 12, player not in another team → inserts membership, marks every other PENDING request **and invitation** of that player CANCELLED. Audit `JOIN_REQUEST_ACCEPTED|REJECTED`. The `/join-requests/{id}/*` actions refuse INVITATION rows (409).
- Invitation (`direction = INVITATION`, `players/application/InvitationService`): sent by the team captain (or ADMIN); team ACTIVE, not locked (roster frozen, 409), with < 12 members; invited user ACTIVE, PLAYER, with a sport profile, not in an ACTIVE team; no other PENDING invitation from the same team to the same player (409). A player may hold pending invitations from several teams. Only the invited player accepts/rejects (403 otherwise); accepting runs exactly the request-acceptance membership step (`JoinRequestService.completeMembership`). Captain or ADMIN may cancel while PENDING. Inactivating a team cancels its pending requests and invitations, and so does approving its tournament registration (`TeamService.closeRecruitment`, audit reason `ROSTER_FROZEN`), because a frozen roster could never accept them. Audit `INVITATION_SENT|ACCEPTED|REJECTED|CANCELLED`.
- Free-agent search: `GET /api/players?position=&available=true` returns players with a profile and no active team.

**teams**
- Create (any PLAYER): name unique, colors; the creator must not be in another ACTIVE team. The creator becomes first member (needs a sport profile; if none, 409 with clear message) and, in the same transaction, captain (CAPTAIN role granted if absent). Audit `TEAM_CREATED` (+ `ROLE_ASSIGNED`).
- Update name/colors: forbidden if team has an APPROVED registration in an ACTIVE or IN_PROGRESS tournament. Audit `TEAM_UPDATED`.
- Add member (`TeamService.addMember`, reached by request/invitation acceptance): refused while the team is locked, so its roster is frozen for the whole tournament.
- Remove member: same lock; captain cannot remove themself. Audit `TEAM_MEMBER_REMOVED`.
- Inactivate team (captain/admin): same lock. Cancels pending requests/invitations of the team, then revokes CAPTAIN from its captain. Audit `TEAM_INACTIVATED` (+ `ROLE_REMOVED`).
- Eligibility check (used at registration): 7 ≤ members ≤ 12; no duplicate jersey numbers; strictly more than half of members with `academic_program != OTHER`; every member has a sport profile.

**tournaments**
- Create (ORGANIZER): status DRAFT. Update fields only in DRAFT. Delete only in DRAFT. Audit `TOURNAMENT_*`.
- Activate: DRAFT → ACTIVE (requires rulebook uploaded and ≥ 1 venue). Start: ACTIVE → IN_PROGRESS from the start date until the end date (`start_date <= today <= end_date`); requires ≥ 2 APPROVED registrations. Finish: IN_PROGRESS → FINISHED when the end date has been reached (`today >= end_date`, as implemented in `TournamentService.finish`) **or** the FINAL has decided the champion: PLAYED, or CANCELLED with a walkover winner (`FinalMatchAdapter`). Refused while SCHEDULED matches remain. Finish locks the tournament row like advance/generate.
- Rulebook: `POST /api/tournaments/{id}/rulebook` multipart PDF (DRAFT/ACTIVE). Venues: CRUD with image, only while not FINISHED.
- Registration (CAPTAIN of the team): tournament ACTIVE, `today <= registration_deadline`, approved count < max_teams, team passes eligibility, no existing non-cancelled/non-rejected registration. Receipt file required (image or PDF). Status UNDER_REVIEW. Audit `REGISTRATION_CREATED`.
- Organizer approve/reject (only from UNDER_REVIEW; approve also re-checks capacity). Captain cancel only from UNDER_REVIEW. Audit.
- Team members of an APPROVED registration in ACTIVE/IN_PROGRESS tournaments are "locked" (see teams/identity).

**competition**
- Format: **group stage round-robin** (every approved team plays every other once, fixtures shuffled randomly, split into rounds using the circle method) → **knockout** from standings: top 8 → QUARTERFINAL (1v8, 2v7, 3v6, 4v5), top 4 → SEMIFINAL, top 2 → FINAL. With N approved teams: N ≥ 8 → quarters; 4 ≤ N < 8 → semis; N < 4 → final only.
- `POST /tournaments/{id}/matches/generate` (ORGANIZER, tournament IN_PROGRESS, no matches yet): generates the group stage. Venues and referees assigned round-robin (cycled) automatically; `scheduled_at` = start_date + round index days at 18:00 by default (organizer edits later).
- `POST /tournaments/{id}/matches/advance` (ORGANIZER): all matches of current phase PLAYED or CANCELLED → generate next phase. Knockout winners: score, then penalties (both required for a draw in knockout; a draw without a decisive shoot-out is refused with **409** explaining which match blocks the bracket). Winners are taken in **bracket order** (the order the phase was drawn in, i.e. match id), never kick-off order, so rescheduling a match cannot change the pairings of the next phase (QF winners W1–W4 and W2–W3 meet in the semi-finals). The knockout draw out of the group stage is taken **once**, from the standings at `advance` time: correcting a group result afterwards is allowed but does not reseed existing knockout matches; if the qualifiers change, undo the knockout phase(s) and advance again (the frontend warns about it).
- **Correction power (product decision, overrides the PDF rule "only future matches, only date/time/venue/referee")**: while the tournament is IN_PROGRESS the ORGANIZER (ADMIN inherits) can fix any match, whatever its status, so every system or human error is recoverable. Outside IN_PROGRESS nothing is editable (FINISHED is read-only). Match state machine: SCHEDULED → PLAYED | CANCELLED, and PLAYED | CANCELLED → SCHEDULED (reopen).
- Update match `PATCH /matches/{id}` (ORGANIZER, IN_PROGRESS, any status): `scheduledAt` (may be in the past, to record what actually happened), `venueId` (a venue of the tournament), `refereeId` (an ACTIVE referee), `homeTeamId`/`awayTeamId` (null = unchanged). Teams only while the match is not PLAYED (409 "Reabra el partido antes de cambiar los equipos: tiene un resultado registrado."); both teams APPROVED in the tournament, distinct, and a team entering the match must not already play another non-CANCELLED match of the same GROUP round or the same knockout phase (409 naming the team). In a knockout phase the walkover winner of a CANCELLED match also occupies its slot (it cannot be entered into another match of that phase). The walkover winner of a cancelled match cannot be replaced without reopening it. A team leaving a knockout match while it already stands in the NEXT knockout phase (it won this match before it was reopened, or went through by walkover there) is refused with 409 "Deshaga la fase {siguiente} antes de cambiar los equipos de este partido: {equipo} ya figura en ella." The lineup of a team that leaves the match is deleted. Audit `MATCH_UPDATED` (team changes record `previousHomeTeamId/homeTeamId/previousAwayTeamId/awayTeamId`).
- Delete match: only with `cancel_reason` DISQUALIFIED|NO_SHOW → status CANCELLED (soft); a knockout match also needs `winnerTeamId` (walkover). Audit `MATCH_CANCELLED`.
- Result `POST /matches/{id}/result` (ORGANIZER, IN_PROGRESS, match SCHEDULED or PLAYED, regardless of later phases): home/away score, optional penalties (a knockout draw needs a decisive shoot-out), events list (goals must reference a member of the scoring team; goals count per team must equal the score). SCHEDULED → PLAYED, audit `MATCH_RESULT_RECORDED`; PLAYED → score, penalties and events replaced, audit `MATCH_RESULT_CORRECTED`.
- Knockout propagation (result, correction, or walkover of a knockout match): the match of the NEXT knockout phase holding one of the two teams is looked up. If it holds the loser instead of the winner and is not PLAYED, the loser is replaced by the winner on the same side and the loser's lineup for it is deleted (audit `MATCH_UPDATED` with `reason: RESULT_CORRECTION`, `sourceMatchId`, `previousTeamId`, `teamId`). If it is PLAYED (or CANCELLED sending the loser through by walkover) the whole request is refused with 409 "El partido de {fase} ya se jugó con {equipo}; reábralo antes de corregir este resultado." The check runs before anything is written, and the use case is one transaction.
- Reopen `POST /matches/{id}/reopen` (ORGANIZER, IN_PROGRESS): PLAYED or CANCELLED → SCHEDULED, clearing score, penalties, events, cancel reason and walkover winner. Audit `MATCH_REOPENED`.
- Undo phase `POST /tournaments/{id}/matches/undo-phase` (ORGANIZER, IN_PROGRESS): deletes every match of the tournament's latest phase when none of them is PLAYED (409 "No se puede deshacer la fase {fase}: ya tiene partidos jugados. Reábralos primero."); events and lineups go with them (`ON DELETE CASCADE`). Works for the group stage too, after which `generate` is allowed again. Audit `PHASE_UNDONE` on entity `TOURNAMENT`.
- `MatchResponse` flags: `resultEditable` = IN_PROGRESS && status ∈ {SCHEDULED, PLAYED}; `teamsEditable` = IN_PROGRESS && status ≠ PLAYED; `reopenable` = IN_PROGRESS && status ∈ {PLAYED, CANCELLED}.
- Finish stays refused while SCHEDULED matches remain; `advance` rules are unchanged; standings are recomputed on every read, so corrections show up immediately in the table (but not in an already drawn knockout phase, see `advance`).
- Lineup (CAPTAIN of a team playing the match, before `scheduled_at`): formation (default F_2_3_1) + exactly 7 starters, all team members. Visible to members of that team, organizer, admin, referee of the match.
- Standings per tournament (computed on read from PLAYED GROUP matches): P, W, D, L, GF, GA, GD, Pts (3/1/0). Order: Pts, GD, GF, name.
- Stats: top scorers (GOAL events grouped by player), match history (PLAYED matches ordered by date), results per team.
- Referee: `GET /api/referees/me/matches`; `GET /api/matches/{id}/sanctioned-players` — a player is sanctioned for a match if in their team's previous PLAYED match they got a RED_CARD, or if the yellow cards of that previous match made their accumulated YELLOW_CARD count in the tournament cross an even threshold (`before / 2 < after / 2`: 1→2, 1→3 and 3→4 suspend, 2→3 does not).
- Bracket view: matches grouped by phase with team names/scores.

### 3.5 REST API (prefix `/api`)

Errors: `{ "timestamp", "status", "error", "message", "path", "traceId", "details": [ {field, message} ]? }`.
`traceId` (12 hex characters) is generated per request by `shared/config/TraceIdFilter` (runs before
the security chain), echoed in the `X-Trace-Id` response header (exposed through CORS), put in the
SLF4J MDC and printed on every log line of the request (`logging.pattern.level`). Every error body
carries it, including the 401/403 written by `SecurityErrorResponses` (same Spanish messages as
`GlobalExceptionHandler`). An unexpected exception is logged at ERROR with its stack trace and the
`traceId`; the client only reads "Ocurrió un error inesperado. Si el problema continúa, comparta el
código de referencia {traceId} con el administrador."
Framework client errors never become a 500: wrong `Content-Type` (e.g. JSON sent to a multipart
endpoint, `text/plain` to a JSON one) → 415; unacceptable `Accept` → 406; unparsable multipart → 400;
oversized upload → 413; missing header / parameter / part, type conversion failures → 400; unknown
path → 404; wrong method → 405; any other Spring `ErrorResponse` with a 4xx status keeps its status
with a generic message. Error bodies are always `application/json`, whatever `Accept` asked for.
Pagination is not needed at this scale; lists return arrays.

```
AUTH / IDENTITY
POST   /auth/register                     {fullName,email,password,schoolRelation?,academicProgram,semester?,birthDate,documentType,documentNumber,initialRole?}  → 201 UserResponse (any e-mail; PLAYER outside the age range → 409)
POST   /auth/login                        {email,password} → {token,expiresAt,user}
POST   /auth/logout                       → 204
GET    /auth/me                           → UserResponse {id,fullName,email,schoolRelation,academicProgram,semester,status,birthDate,documentType,documentNumber,roles[],hasProfile,teamId?}
GET    /admin/users?search=               ADMIN,ORGANIZER → UserResponse[]
GET    /admin/users/{id}/roles            ADMIN → Role[]
POST   /admin/users/{id}/roles            ADMIN {role} → Role[]
DELETE /admin/users/{id}/roles/{role}     ADMIN → Role[]
POST   /admin/users/{id}/inactivate       ADMIN → UserResponse
POST   /organizer/referees                ORGANIZER,ADMIN {fullName,email,password,birthDate,documentType,documentNumber} → 201 UserResponse
GET    /organizer/referees                ORGANIZER,ADMIN → UserResponse[]
GET    /admin/audit?action=&limit=        ADMIN → AuditLogResponse[]

USERS & PLAYERS
GET    /users/{id}                        auth → UserResponse
PATCH  /users/{id}                        self|ADMIN {fullName,schoolRelation,academicProgram,semester} → UserResponse (non-admin changing schoolRelation → 403)
GET    /players/me/profile                PLAYER → PlayerProfileResponse {userId,fullName,position,jerseyNumber,photoFileId,teamId?,teamName?}
PUT    /players/me/profile                PLAYER {position,jerseyNumber} → PlayerProfileResponse (create or update)
POST   /players/me/profile/photo          PLAYER multipart "file" → PlayerProfileResponse
GET    /players/{userId}/profile          auth → PlayerProfileResponse
GET    /players?position=&available=true  auth → PlayerProfileResponse[]
POST   /teams/{teamId}/join-requests      PLAYER {message?} → 201 JoinRequestResponse {id,teamId,teamName,playerId,playerName,position,jerseyNumber,status,direction,message,createdAt}
GET    /players/me/join-requests          PLAYER → JoinRequestResponse[] (direction REQUEST only)
POST   /join-requests/{id}/cancel         PLAYER (owner) → JoinRequestResponse
GET    /teams/{teamId}/join-requests?status=PENDING   CAPTAIN (of team) → JoinRequestResponse[] (direction REQUEST only)
POST   /join-requests/{id}/accept         CAPTAIN → JoinRequestResponse   (INVITATION rows → 409, also for reject/cancel)
POST   /join-requests/{id}/reject         CAPTAIN → JoinRequestResponse
POST   /teams/{teamId}/invitations        CAPTAIN (of team)|ADMIN {playerId,message?} → 201 JoinRequestResponse (direction INVITATION)
GET    /teams/{teamId}/invitations?status=PENDING|ACCEPTED|REJECTED|CANCELLED   CAPTAIN (of team)|ADMIN → JoinRequestResponse[] newest first
GET    /players/me/invitations            PLAYER → JoinRequestResponse[] (every status, newest first)
POST   /invitations/{id}/accept           invited player → JoinRequestResponse (joins the team)
POST   /invitations/{id}/reject           invited player → JoinRequestResponse
POST   /invitations/{id}/cancel           CAPTAIN (of team)|ADMIN → JoinRequestResponse

TEAMS
POST   /teams                             PLAYER {name,colors} → 201 TeamResponse (creator becomes CAPTAIN) {id,name,colors,status,captain{id,fullName},members[{userId,fullName,position,jerseyNumber,academicProgram,photoFileId}],memberCount,locked}
GET    /teams                             auth → TeamResponse[] (summary)
GET    /teams/mine                        auth → TeamResponse | 404
GET    /teams/{id}                        auth → TeamResponse
PATCH  /teams/{id}                        CAPTAIN {name?,colors?} → TeamResponse
DELETE /teams/{id}/members/{userId}       CAPTAIN → TeamResponse
POST   /teams/{id}/inactivate             CAPTAIN|ADMIN → TeamResponse
GET    /teams/{id}/eligibility            auth → {eligible, problems[]}

TOURNAMENTS
GET    /tournaments                       public → TournamentResponse[] {id,name,startDate,endDate,registrationDeadline,maxTeams,fee,status,rulebookFileId,venues[],approvedTeams}
GET    /tournaments/current               public → TournamentResponse | 404 (latest ACTIVE or IN_PROGRESS)
GET    /tournaments/{id}                  public → TournamentResponse
POST   /tournaments                       ORGANIZER {name,startDate,endDate,registrationDeadline,maxTeams,fee} → 201
PATCH  /tournaments/{id}                  ORGANIZER (DRAFT) → TournamentResponse
DELETE /tournaments/{id}                  ORGANIZER (DRAFT) → 204
POST   /tournaments/{id}/activate|start|finish   ORGANIZER → TournamentResponse
POST   /tournaments/{id}/rulebook         ORGANIZER multipart "file" (pdf) → TournamentResponse
POST   /tournaments/{id}/venues           ORGANIZER multipart {name,description,file?} → 201 VenueResponse {id,name,description,imageFileId}
DELETE /tournaments/{id}/venues/{venueId} ORGANIZER → 204
POST   /tournaments/{id}/registrations    CAPTAIN multipart "file" → 201 RegistrationResponse {id,tournamentId,teamId,teamName,receiptFileId,status,reviewNote,createdAt,reviewedAt}
GET    /tournaments/{id}/registrations    ORGANIZER → RegistrationResponse[]
GET    /tournaments/{id}/registrations/mine   CAPTAIN → RegistrationResponse | 404
POST   /registrations/{id}/approve        ORGANIZER {note?} → RegistrationResponse
POST   /registrations/{id}/reject         ORGANIZER {note?} → RegistrationResponse
POST   /registrations/{id}/cancel         CAPTAIN → RegistrationResponse

COMPETITION
POST   /tournaments/{id}/matches/generate ORGANIZER → MatchResponse[]
POST   /tournaments/{id}/matches/advance  ORGANIZER → MatchResponse[]
POST   /tournaments/{id}/matches/undo-phase  ORGANIZER (IN_PROGRESS) → {phase, deletedMatches}
GET    /tournaments/{id}/matches?phase=   public → MatchResponse[] {id,tournamentId,phase,roundNumber,homeTeam{id,name,colors},awayTeam{...},venue{id,name}?,referee{id,fullName}?,scheduledAt,status,homeScore,awayScore,homePenalties,awayPenalties,cancelReason,walkoverWinnerTeamId,resultEditable,teamsEditable,reopenable,events[{id,teamId,playerId,playerName,type,minute}]}
GET    /matches/{id}                      public → MatchResponse
PATCH  /matches/{id}                      ORGANIZER (IN_PROGRESS) {scheduledAt?,venueId?,refereeId?,homeTeamId?,awayTeamId?} → MatchResponse
DELETE /matches/{id}?reason=NO_SHOW&winnerTeamId=   ORGANIZER → MatchResponse (CANCELLED; winnerTeamId required for knockout)
POST   /matches/{id}/result               ORGANIZER {homeScore,awayScore,homePenalties?,awayPenalties?,events[{teamId,playerId,type,minute?}]} → MatchResponse (record or correct)
POST   /matches/{id}/reopen               ORGANIZER (IN_PROGRESS) → MatchResponse (PLAYED|CANCELLED → SCHEDULED)
PUT    /matches/{id}/lineups              CAPTAIN {formation,starterIds[]} → LineupResponse {matchId,teamId,formation,starters[{userId,fullName,position,jerseyNumber}],substitutes[...]}
GET    /matches/{id}/lineups/{teamId}     team members|ORGANIZER|ADMIN|match referee → LineupResponse | 404
GET    /tournaments/{id}/standings        public → StandingRow[] {position,teamId,teamName,played,won,drawn,lost,goalsFor,goalsAgainst,goalDifference,points}
GET    /tournaments/{id}/bracket          public → {phases:[{phase,matches:MatchResponse[]}]}
GET    /tournaments/{id}/stats/top-scorers   public → [{playerId,playerName,teamId,teamName,goals}]
GET    /tournaments/{id}/stats/history       public → MatchResponse[] (PLAYED, by date desc)
GET    /tournaments/{id}/teams/{teamId}/results  public → MatchResponse[]
GET    /referees/me/matches               REFEREE → MatchResponse[]
GET    /matches/{id}/sanctioned-players   REFEREE|ORGANIZER|ADMIN → [{userId,fullName,teamId,teamName,reason}]

FILES & HOME
GET    /files/{id}                        auth → binary (Content-Type from GridFS metadata)
GET    /home                              auth → {tournament?:TournamentResponse, myTeam?:TeamResponse, myRegistration?:RegistrationResponse, upcomingMatches:MatchResponse[], standingsTop:StandingRow[]}
```

OpenAPI via springdoc at `/swagger-ui.html`.

## 4. Frontend

```
frontend/src/
  app/            router.tsx, providers.tsx, App.tsx
  lib/            api client (fetch wrapper with bearer token, error normalization), formatters
  store/          auth.store.ts (Zustand, persisted: token + user), ui.store.ts
  types/          api.ts (mirrors DTOs above)
  components/     atomic design
    atoms/        Button, Input, Select, Badge, Spinner, Avatar, FileInput
    molecules/    FormField, Card, StatTile, Modal, Table, EmptyState, Alert
    organisms/    Navbar, Sidebar, StandingsTable, BracketView, MatchCard, TeamRoster, LineupPitch
    templates/    AppShell, AuthLayout
  features/       one folder per backend module; container (hooks/pages) vs presentational split
    auth/         LoginPage, RegisterPage, useAuth
    home/         HomePage
    players/      ProfilePage, FreeAgentsPage, JoinRequestsPage (requests + invitations)
    teams/        MyTeamPage, TeamsPage, TeamDetailPage, TeamRequestsPage
    tournaments/  TournamentsPage, TournamentDetailPage (tabs: info, venues, standings, bracket, matches, stats),
                  OrganizerTournamentPage (manage, registrations review), RegisterTeamPage
    competition/  MatchDetailPage, LineupPage, ResultFormPage, RefereeMatchesPage
    admin/        UsersPage (roles), AuditPage
```

- Route guards by role (`RequireRole`). Navbar adapts to roles.
- Server state: simple custom hooks with `useEffect` + api client (no extra library); Zustand only for session and UI.
- Tailwind v4 via `@tailwindcss/vite`. Mobile-first, one accent color, consistent spacing.
- Files render through `GET /api/files/{id}` with the bearer token (fetch → blob URL helper `useFileUrl`).
- Vite dev proxy `/api` → `http://localhost:8080`.

## 5. Local development

```
docker compose up -d            # postgres on host 5433, mongo on host 27018
cd backend && ./mvnw spring-boot:run
cd frontend && pnpm install && pnpm dev
```

Env (backend `application.yml` with env overrides): `DB_URL, DB_USER, DB_PASSWORD, MONGO_URI, JWT_SECRET, JWT_EXPIRATION_MINUTES, CORS_ORIGINS, APP_TIME_ZONE, APP_PLAYER_MIN_AGE, APP_PLAYER_MAX_AGE, ADMIN_EMAIL, ADMIN_PASSWORD`.

## 6. Testing strategy

- Backend: unit tests for application services and domain rules (JUnit 5 + Mockito), focused on the business rules in 3.4 (eligibility, state transitions, standings, bracket generation, sanctions). One `@SpringBootTest` context test using Testcontainers is optional; not required for the grade.
- Frontend: Vitest + Testing Library for critical presentational components and the auth store.
