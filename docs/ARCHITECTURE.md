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
  config/         CorsConfig, OpenApiConfig, JacksonConfig, AppProperties
  security/       SecurityConfig, JwtService, JwtAuthenticationFilter, CurrentUser (resolver), Roles
  exception/      GlobalExceptionHandler, ApiError, NotFoundException, ConflictException,
                  ForbiddenOperationException, BusinessRuleException
  audit/          AuditLog (entity), AuditAction (enum), AuditService, AuditRepository, AuditController (admin)
  storage/        FileStorage (port), GridFsFileStorage (adapter), StoredFile, FileController
  home/           HomeController, HomeService
identity/         register, login, logout, roles, inactivate
players/          user profile update, sport profile, join requests, free-agent search
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
- Passwords: BCrypt.
- Email policy: `app.institutional-domains` (default `escuelaing.edu.co, mail.escuelaing.edu.co`).
  `STUDENT|PROFESSOR|ADMINISTRATIVE|GRADUATE` must use an institutional domain; `FAMILY` must NOT.
- Admin is created at startup by `identity/infrastructure/AdminBootstrap` when no `ADMIN` user exists, from `ADMIN_EMAIL` / `ADMIN_PASSWORD` (dev defaults `admin@escuelaing.edu.co` / `Admin123*`, logged with a warning). The `prod` profile (`ProductionGuard`) refuses the dev admin password and the dev JWT secret.
- Login is throttled: 5 failures per e-mail or per client IP in 15 minutes → 429, audited as `LOGIN_FAILED`. Duplicate e-mail/document at registration share one generic message (no account enumeration).
- Public (no token): `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/files/{id}`? → **No**, files require auth. Public read-only: `GET /api/tournaments/**` (list, detail, standings, bracket, stats, matches) so anyone can see the tournament. Everything else authenticated.
- Logout: audited, and the presented token's `jti` goes into an in-memory `TokenDenylist` until its expiry; the filter rejects denylisted tokens with 401.
- Personal data (`email`, `birthDate`, `documentType`, `documentNumber`) is returned only to the user themself or an ADMIN (`UserService.toResponse(user, viewer)`); organizers get the e-mail in `GET /admin/users` and `GET /organizer/referees` only. `GET /players` and `GET /players/{id}/profile` require CAPTAIN or ORGANIZER.
- Files: uploads are validated by magic bytes and stored with `kind` + owner metadata in GridFS; `GET /files/{id}` serves receipts only to ORGANIZER/ADMIN or the registering captain (`FileAccessPolicy` port implemented in `tournaments/infrastructure`), adds `X-Content-Type-Options: nosniff` and sends PDFs as attachments. Replaced/orphaned binaries are deleted after the DB transaction commits.

### 3.3 Data model (PostgreSQL, Flyway `V1__schema.sql`)

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
  created_at, resolved_at NULL
  -- service enforces: at most ONE PENDING request per player.

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
- Register: captures all spec fields; `initialRole` ∈ {PLAYER, GUEST}; `semester` required iff STUDENT; email domain policy; status ACTIVE. Audit `USER_REGISTERED`.
- Login → JWT. Audit `LOGIN`. Logout audit `LOGOUT`.
- Admin assigns/removes/lists any role (except cannot remove own ADMIN).
- Organizer can grant/revoke `CAPTAIN` and create `REFEREE` users; organizer can never grant `ADMIN` or `ORGANIZER`.
- Inactivate user (admin): forbidden if user is a member of a team with an APPROVED registration in a tournament with status ACTIVE or IN_PROGRESS. Audit `USER_INACTIVATED`.

**players**
- Update basic info (self or admin): fullName, schoolRelation, academicProgram, semester. Email/password immutable. Audit `USER_UPDATED`.
- Sport profile create/update (PLAYER): position, jerseyNumber, photo. Update forbidden while member of an ACTIVE team. Never deletable. Audit `PROFILE_CREATED|PROFILE_UPDATED`.
- Join request (PLAYER with profile, not in a team): at most one PENDING at a time; team must be ACTIVE and have < 12 members. Player may cancel while PENDING.
- Captain accepts/rejects. Accept → validates jersey uniqueness within team, team size < 12, player not in another team → inserts membership, marks other pending requests of that player CANCELLED. Audit `JOIN_REQUEST_ACCEPTED|REJECTED`.
- Free-agent search: `GET /api/players?position=&available=true` returns players with a profile and no active team.

**teams**
- Create (CAPTAIN): name unique, colors. Captain becomes first member (needs a sport profile; if the captain has none, 409 with clear message). Audit `TEAM_CREATED`.
- Update name/colors: forbidden if team has an APPROVED registration in an ACTIVE or IN_PROGRESS tournament. Audit `TEAM_UPDATED`.
- Remove member: same lock; captain cannot remove themself. Audit `TEAM_MEMBER_REMOVED`.
- Inactivate team (captain/admin): same lock. Audit `TEAM_INACTIVATED`.
- Eligibility check (used at registration): 7 ≤ members ≤ 12; no duplicate jersey numbers; strictly more than half of members with `academic_program != OTHER`; every member has a sport profile.

**tournaments**
- Create (ORGANIZER): status DRAFT. Update fields only in DRAFT. Delete only in DRAFT. Audit `TOURNAMENT_*`.
- Activate: DRAFT → ACTIVE (requires rulebook uploaded and ≥ 1 venue). Start: ACTIVE → IN_PROGRESS only if `start_date == today`; requires ≥ 2 APPROVED registrations. Finish: IN_PROGRESS → FINISHED only if `end_date <= today`... spec says "fecha final es igual o posterior a la fecha actual" → allowed when `end_date >= today`? Read literally it means finishing is allowed while the tournament has not passed its end date; we implement `today <= end_date` OR final match played. Keep it simple: allow FINISHED when `end_date >= today` **or** the FINAL match is PLAYED.
- Rulebook: `POST /api/tournaments/{id}/rulebook` multipart PDF (DRAFT/ACTIVE). Venues: CRUD with image, only while not FINISHED.
- Registration (CAPTAIN of the team): tournament ACTIVE, `today <= registration_deadline`, approved count < max_teams, team passes eligibility, no existing non-cancelled/non-rejected registration. Receipt file required (image or PDF). Status UNDER_REVIEW. Audit `REGISTRATION_CREATED`.
- Organizer approve/reject (only from UNDER_REVIEW; approve also re-checks capacity). Captain cancel only from UNDER_REVIEW. Audit.
- Team members of an APPROVED registration in ACTIVE/IN_PROGRESS tournaments are "locked" (see teams/identity).

**competition**
- Format: **group stage round-robin** (every approved team plays every other once, fixtures shuffled randomly, split into rounds using the circle method) → **knockout** from standings: top 8 → QUARTERFINAL (1v8, 2v7, 3v6, 4v5), top 4 → SEMIFINAL, top 2 → FINAL. With N approved teams: N ≥ 8 → quarters; 4 ≤ N < 8 → semis; N < 4 → final only.
- `POST /tournaments/{id}/matches/generate` (ORGANIZER, tournament IN_PROGRESS, no matches yet): generates the group stage. Venues and referees assigned round-robin (cycled) automatically; `scheduled_at` = start_date + round index days at 18:00 by default (organizer edits later).
- `POST /tournaments/{id}/matches/advance` (ORGANIZER): all matches of current phase PLAYED or CANCELLED → generate next phase. Knockout winners: score, then penalties (both required for a draw in knockout; 400 otherwise).
- Update match (ORGANIZER): only `scheduled_at`, `venue_id`, `referee_user_id`, and only if `scheduled_at > now`. Delete match: only with `cancel_reason` DISQUALIFIED|NO_SHOW → status CANCELLED (soft). Audit `MATCH_*`.
- Result (ORGANIZER, match SCHEDULED): home/away score, optional penalties, events list (goals must reference a member of the scoring team; goals count per team must equal the score). Sets PLAYED. Audit `MATCH_RESULT_RECORDED`.
- Lineup (CAPTAIN of a team playing the match, before `scheduled_at`): formation (default F_2_3_1) + exactly 7 starters, all team members. Visible to members of that team, organizer, admin, referee of the match.
- Standings per tournament (computed on read from PLAYED GROUP matches): P, W, D, L, GF, GA, GD, Pts (3/1/0). Order: Pts, GD, GF, name.
- Stats: top scorers (GOAL events grouped by player), match history (PLAYED matches ordered by date), results per team.
- Referee: `GET /api/referees/me/matches`; `GET /api/matches/{id}/sanctioned-players` — a player is sanctioned for a match if in their team's previous PLAYED match they got a RED_CARD, or if their accumulated YELLOW_CARD count in the tournament reached an even number ≥ 2 in that previous match.
- Bracket view: matches grouped by phase with team names/scores.

### 3.5 REST API (prefix `/api`)

Errors: `{ "timestamp", "status", "error", "message", "path", "details": [ {field, message} ]? }`.
Pagination is not needed at this scale; lists return arrays.

```
AUTH / IDENTITY
POST   /auth/register                     {fullName,email,password,schoolRelation,academicProgram,semester?,birthDate,documentType,documentNumber,initialRole}  → 201 UserResponse
POST   /auth/login                        {email,password} → {token,expiresAt,user}
POST   /auth/logout                       → 204
GET    /auth/me                           → UserResponse {id,fullName,email,schoolRelation,academicProgram,semester,status,birthDate,documentType,documentNumber,roles[],hasProfile,teamId?}
GET    /admin/users?search=               ADMIN,ORGANIZER → UserResponse[]
GET    /admin/users/{id}/roles            ADMIN → Role[]
POST   /admin/users/{id}/roles            ADMIN {role} → Role[]
DELETE /admin/users/{id}/roles/{role}     ADMIN → Role[]
POST   /admin/users/{id}/inactivate       ADMIN → UserResponse
POST   /organizer/users/{id}/captain      ORGANIZER,ADMIN → UserResponse   (grant CAPTAIN)
DELETE /organizer/users/{id}/captain      ORGANIZER,ADMIN → UserResponse
POST   /organizer/referees                ORGANIZER,ADMIN {fullName,email,password,birthDate,documentType,documentNumber} → 201 UserResponse
GET    /organizer/referees                ORGANIZER,ADMIN → UserResponse[]
GET    /admin/audit?action=&limit=        ADMIN → AuditLogResponse[]

USERS & PLAYERS
GET    /users/{id}                        auth → UserResponse
PATCH  /users/{id}                        self|ADMIN {fullName,schoolRelation,academicProgram,semester} → UserResponse
GET    /players/me/profile                PLAYER → PlayerProfileResponse {userId,fullName,position,jerseyNumber,photoFileId,teamId?,teamName?}
PUT    /players/me/profile                PLAYER {position,jerseyNumber} → PlayerProfileResponse (create or update)
POST   /players/me/profile/photo          PLAYER multipart "file" → PlayerProfileResponse
GET    /players/{userId}/profile          auth → PlayerProfileResponse
GET    /players?position=&available=true  auth → PlayerProfileResponse[]
POST   /teams/{teamId}/join-requests      PLAYER {message?} → 201 JoinRequestResponse {id,teamId,teamName,playerId,playerName,position,jerseyNumber,status,message,createdAt}
GET    /players/me/join-requests          PLAYER → JoinRequestResponse[]
POST   /join-requests/{id}/cancel         PLAYER (owner) → JoinRequestResponse
GET    /teams/{teamId}/join-requests?status=PENDING   CAPTAIN (of team) → JoinRequestResponse[]
POST   /join-requests/{id}/accept         CAPTAIN → JoinRequestResponse
POST   /join-requests/{id}/reject         CAPTAIN → JoinRequestResponse

TEAMS
POST   /teams                             CAPTAIN {name,colors} → 201 TeamResponse {id,name,colors,status,captain{id,fullName},members[{userId,fullName,position,jerseyNumber,academicProgram,photoFileId}],memberCount,locked}
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
GET    /tournaments/{id}/matches?phase=   public → MatchResponse[] {id,tournamentId,phase,roundNumber,homeTeam{id,name,colors},awayTeam{...},venue{id,name}?,referee{id,fullName}?,scheduledAt,status,homeScore,awayScore,homePenalties,awayPenalties,cancelReason,events[{id,teamId,playerId,playerName,type,minute}]}
GET    /matches/{id}                      public → MatchResponse
PATCH  /matches/{id}                      ORGANIZER {scheduledAt?,venueId?,refereeId?} → MatchResponse
DELETE /matches/{id}?reason=NO_SHOW       ORGANIZER → MatchResponse (CANCELLED)
POST   /matches/{id}/result               ORGANIZER {homeScore,awayScore,homePenalties?,awayPenalties?,events[{teamId,playerId,type,minute?}]} → MatchResponse
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
    players/      ProfilePage, FreeAgentsPage, JoinRequestsPage
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

Env (backend `application.yml` with env overrides): `DB_URL, DB_USER, DB_PASSWORD, MONGO_URI, JWT_SECRET, JWT_EXPIRATION_MINUTES, CORS_ORIGINS`.

## 6. Testing strategy

- Backend: unit tests for application services and domain rules (JUnit 5 + Mockito), focused on the business rules in 3.4 (eligibility, state transitions, standings, bracket generation, sanctions). One `@SpringBootTest` context test using Testcontainers is optional; not required for the grade.
- Frontend: Vitest + Testing Library for critical presentational components and the auth store.
